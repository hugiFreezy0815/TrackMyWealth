package com.trackmywealth.backend.service;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * #276: reads a scanned document through a local Tesseract process - each page goes in as PNG on
 * stdin and its text comes back on stdout, so no document file is ever written to disk, and nothing
 * leaves the server. Tesseract's diagnostics are discarded: they can quote the recognized text.
 *
 * <p>Off unless {@code app.import.ocr.enabled} is set (#276): OCR misreads digits into amounts that
 * still parse, and until #276 adds a confidence threshold below which a row is an error, nothing
 * would flag them. While off, every recognition is a 503 {@code IMPORT_OCR_UNAVAILABLE}.
 *
 * <p>Bounded: at most {@value #MAX_CONCURRENT} documents at a time (a further request is a 503, not
 * a queue). A document holds its slot from its first page to its last, and a page is rendered only
 * while the slot is held, so a busy server never renders a page it then cannot read. A page may
 * take {@value #PAGE_TIMEOUT_SECONDS} s, a document {@value #DOCUMENT_TIMEOUT_SECONDS} s including
 * its rendering, and the output is at most {@value #MAX_TEXT} characters.
 */
@Service
public class LocalOcrService {

  static final int MAX_CONCURRENT = 2;
  static final int PAGE_TIMEOUT_SECONDS = 30;
  static final int DOCUMENT_TIMEOUT_SECONDS = 120;
  static final int MAX_TEXT = 2_000_000;

  private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
  // Two pipe threads (stdin, stdout) per running recognition.
  private final ExecutorService pipes =
      Executors.newFixedThreadPool(
          2 * MAX_CONCURRENT,
          runnable -> {
            Thread thread = new Thread(runnable, "ocr-pipe");
            thread.setDaemon(true);
            return thread;
          });
  private final boolean enabled;
  private final String executable;
  private final String languages;
  private final long pageTimeoutMillis;
  private final long documentTimeoutMillis;

  @Autowired
  public LocalOcrService(
      @Value("${app.import.ocr.enabled:false}") boolean enabled,
      @Value("${app.import.ocr.executable:tesseract}") String executable,
      @Value("${app.import.ocr.languages:eng+deu}") String languages) {
    this(
        enabled,
        executable,
        languages,
        TimeUnit.SECONDS.toMillis(PAGE_TIMEOUT_SECONDS),
        TimeUnit.SECONDS.toMillis(DOCUMENT_TIMEOUT_SECONDS));
  }

  // Tests shorten the time limits.
  LocalOcrService(
      boolean enabled,
      String executable,
      String languages,
      long pageTimeoutMillis,
      long documentTimeoutMillis) {
    this.enabled = enabled;
    this.executable = executable;
    this.languages = languages;
    this.pageTimeoutMillis = pageTimeoutMillis;
    this.documentTimeoutMillis = documentTimeoutMillis;
  }

  @PreDestroy
  void shutdown() {
    pipes.shutdownNow();
  }

  /**
   * The text Tesseract recognizes on pages {@code 0 .. pageCount - 1}, each produced by {@code
   * renderPage} only once this document holds a recognition slot.
   *
   * @throws ApiException 503 {@code IMPORT_OCR_UNAVAILABLE} when OCR is switched off, not
   *     installed, busy or does not finish in time, 422 {@code IMPORT_OCR_FAILED} when it fails on
   *     a page or returns too much text
   */
  public String recognizePages(int pageCount, IntFunction<BufferedImage> renderPage) {
    if (!enabled) {
      throw unavailable("Text recognition is switched off on this server.", null);
    }
    if (!slots.tryAcquire()) {
      throw unavailable("Text recognition is busy. Try again in a moment.", null);
    }
    try {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(documentTimeoutMillis);
      StringBuilder text = new StringBuilder();
      for (int page = 0; page < pageCount; page++) {
        requireTimeLeft(deadline);
        BufferedImage image = renderPage.apply(page);
        // Rendering counts against the document's time too: a page slow to render must not hold
        // the slot past the deadline and then still get a full page timeout.
        long timeout = Math.min(pageTimeoutMillis, requireTimeLeft(deadline));
        text.append(run(image, timeout)).append('\n');
        if (text.length() > MAX_TEXT) {
          throw failed(null);
        }
      }
      return text.toString();
    } finally {
      slots.release();
    }
  }

  // The milliseconds left before deadline; a 503 when none are.
  private static long requireTimeLeft(long deadline) {
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    if (remaining <= 0) {
      throw tooSlow(null);
    }
    return remaining;
  }

  private String run(BufferedImage image, long timeoutMillis) {
    Process process;
    try {
      process =
          new ProcessBuilder(executable, "stdin", "stdout", "-l", languages, "--psm", "6")
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException e) {
      throw unavailable("Text recognition is not installed on this server.", e);
    }
    // Writing the image and reading the text run in parallel, so neither pipe can fill up and
    // block the other side.
    CompletableFuture<Void> input =
        CompletableFuture.runAsync(
            withMdc(() -> writePng(image, process.getOutputStream())), pipes);
    CompletableFuture<String> output =
        CompletableFuture.supplyAsync(withMdc(() -> readText(process.getInputStream())), pipes);
    try {
      if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        throw tooSlow(null);
      }
      if (process.exitValue() != 0) {
        throw failed(null);
      }
      input.get(1, TimeUnit.SECONDS);
      return output.get(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failed(e);
    } catch (ExecutionException e) {
      throw e.getCause() instanceof ApiException apiException ? apiException : failed(e);
    } catch (TimeoutException e) {
      throw tooSlow(e);
    } finally {
      process.destroyForcibly();
      input.cancel(true);
      output.cancel(true);
    }
  }

  // The pipe threads log nothing today; the caller's correlation id still travels with them, as
  // development-standards.md asks of work started on another executor.
  private static Runnable withMdc(Runnable task) {
    Supplier<Void> supplier =
        withMdc(
            () -> {
              task.run();
              return null;
            });
    return supplier::get;
  }

  private static <T> Supplier<T> withMdc(Supplier<T> task) {
    Map<String, String> context = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> previous = MDC.getCopyOfContextMap();
      if (context == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(context);
      }
      try {
        return task.get();
      } finally {
        if (previous == null) {
          MDC.clear();
        } else {
          MDC.setContextMap(previous);
        }
      }
    };
  }

  private static void writePng(BufferedImage image, OutputStream stdin) {
    try (OutputStream stream = stdin;
        MemoryCacheImageOutputStream png = new MemoryCacheImageOutputStream(stream)) {
      ImageIO.write(image, "png", png);
    } catch (IOException e) {
      throw failed(e);
    }
  }

  private static String readText(InputStream stdout) {
    try (InputStream stream = stdout) {
      byte[] bytes = stream.readNBytes(MAX_TEXT + 1);
      if (bytes.length > MAX_TEXT) {
        throw failed(null);
      }
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw failed(e);
    }
  }

  private static ApiException unavailable(String detail, Throwable cause) {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.IMPORT_OCR_UNAVAILABLE, detail, cause);
  }

  // The server's own time limit, not a fault of the file: retryable, like a busy server.
  private static ApiException tooSlow(Throwable cause) {
    return unavailable(
        "Text recognition did not finish in time. Try again later, or with fewer pages.", cause);
  }

  private static ImportFileRejectedException failed(Throwable cause) {
    return new ImportFileRejectedException(
        ApiErrorCode.IMPORT_OCR_FAILED, "Text recognition could not read the scanned page.", cause);
  }
}
