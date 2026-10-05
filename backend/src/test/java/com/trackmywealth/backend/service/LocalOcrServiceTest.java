package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #276: the local OCR process contract, with a stand-in script instead of Tesseract - image on
 * stdin, text on stdout, the configured language models, the slot and time limits, and failures
 * that never echo the process's diagnostics.
 */
class LocalOcrServiceTest {

  @TempDir Path directory;

  private final BufferedImage page = new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_GRAY);
  private final IntFunction<BufferedImage> onePage = i -> page;

  @Test
  void aMissingExecutableIsUnavailableWithAStableCode() {
    LocalOcrService ocr = new LocalOcrService(true, directory.resolve("absent").toString(), "eng");

    assertUnavailable(() -> ocr.recognizePages(1, onePage));
  }

  /**
   * Off by default until #276 adds a confidence threshold: nothing is rendered and no process is
   * started, and the answer is the same retryable 503 as a server without Tesseract.
   */
  @Test
  void switchedOffItRecognizesNothing() throws IOException {
    Path started = directory.resolve("started");
    Path script = script("touch '" + started + "'\ncat >/dev/null\nprintf 'x'\n");
    LocalOcrService ocr = new LocalOcrService(false, script.toString(), "eng");
    AtomicInteger rendered = new AtomicInteger();

    assertUnavailable(
        () ->
            ocr.recognizePages(
                1,
                i -> {
                  rendered.incrementAndGet();
                  return page;
                }));
    assertThat(rendered.get()).isZero();
    assertThat(started).doesNotExist();
  }

  @Test
  void readsEachPageFromStdinAndItsTextFromStdoutWithTheConfiguredLanguages() throws IOException {
    Path script =
        script(
            "[ \"$1\" = stdin ] && [ \"$2\" = stdout ] && [ \"$4\" = eng+deu ] || exit 1\n"
                + "cat >/dev/null\n"
                + "printf 'Invented statement\\n'\n");

    assertThat(new LocalOcrService(true, script.toString(), "eng+deu").recognizePages(2, onePage))
        .isEqualTo("Invented statement\n\nInvented statement\n\n");
  }

  @Test
  void aFailingProcessIsRejectedWithoutItsDiagnostics() throws IOException {
    Path script = script("cat >/dev/null\nprintf 'invented diagnostics' >&2\nexit 1\n");

    assertThatThrownBy(
            () -> new LocalOcrService(true, script.toString(), "eng").recognizePages(1, onePage))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_OCR_FAILED);
              assertThat(e.getMessage()).doesNotContain("invented diagnostics");
            });
  }

  @Test
  void moreTextThanTheLimitIsRejected() throws IOException {
    Path script =
        script(
            "cat >/dev/null\nhead -c "
                + (LocalOcrService.MAX_TEXT + 1)
                + " /dev/zero | tr '\\0' 'x'\n");

    assertThatThrownBy(
            () -> new LocalOcrService(true, script.toString(), "eng").recognizePages(1, onePage))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_OCR_FAILED));
  }

  /** A page over the server's time limit is the server's limit, not the file's fault: a 503. */
  @Test
  void aPageThatTakesTooLongIsUnavailable() throws IOException {
    Path script = script("cat >/dev/null\nsleep 10\n");
    LocalOcrService ocr = new LocalOcrService(true, script.toString(), "eng", 300, 5_000);

    assertUnavailable(() -> ocr.recognizePages(1, onePage));
  }

  @Test
  void aDocumentThatTakesTooLongIsUnavailable() throws IOException {
    Path script = script("cat >/dev/null\nsleep 0.4\nprintf 'x'\n");
    LocalOcrService ocr = new LocalOcrService(true, script.toString(), "eng", 5_000, 600);
    AtomicInteger rendered = new AtomicInteger();

    assertUnavailable(
        () ->
            ocr.recognizePages(
                10,
                i -> {
                  rendered.incrementAndGet();
                  return page;
                }));
    assertThat(rendered.get()).as("stopped at the deadline, not after every page").isLessThan(4);
  }

  /**
   * F4 of the PR #267 review: rendering counts against the document's time. A page that renders
   * past the deadline is a 503 before its recognition starts, not a further full page timeout.
   */
  @Test
  void aPageThatRendersPastTheDeadlineIsNotRecognized() throws IOException {
    Path started = directory.resolve("started");
    Path script = script("touch '" + started + "'\ncat >/dev/null\nprintf 'x'\n");
    LocalOcrService ocr = new LocalOcrService(true, script.toString(), "eng", 5_000, 200);

    assertUnavailable(
        () ->
            ocr.recognizePages(
                1,
                i -> {
                  try {
                    Thread.sleep(400);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return page;
                }));
    assertThat(started).doesNotExist();
  }

  /**
   * B1 of the PR #267 review: a document holds its slot from its first page on, and a request that
   * finds every slot taken is refused before it renders anything.
   */
  @Test
  void aBusyServerRendersNothingForAFurtherDocument() throws Exception {
    LocalOcrService ocr = new LocalOcrService(true, directory.resolve("absent").toString(), "eng");
    CountDownLatch rendering = new CountDownLatch(LocalOcrService.MAX_CONCURRENT);
    CountDownLatch release = new CountDownLatch(1);
    IntFunction<BufferedImage> blockingRender =
        i -> {
          rendering.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return page;
        };
    ExecutorService callers = Executors.newFixedThreadPool(LocalOcrService.MAX_CONCURRENT);
    try {
      Future<?>[] running = new Future<?>[LocalOcrService.MAX_CONCURRENT];
      for (int i = 0; i < running.length; i++) {
        running[i] = callers.submit(() -> ocr.recognizePages(1, blockingRender));
      }
      assertThat(rendering.await(10, TimeUnit.SECONDS)).isTrue();

      AtomicInteger rendered = new AtomicInteger();
      assertUnavailable(
          () ->
              ocr.recognizePages(
                  1,
                  i -> {
                    rendered.incrementAndGet();
                    return page;
                  }));
      assertThat(rendered.get()).isZero();
    } finally {
      release.countDown();
      callers.shutdown();
      assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void assertUnavailable(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_OCR_UNAVAILABLE);
              assertThat(e.getStatusCode().value()).isEqualTo(503);
            });
  }

  private Path script(String body) throws IOException {
    Path script = directory.resolve("stand-in-ocr");
    Files.writeString(script, "#!/bin/sh\n" + body);
    assertThat(script.toFile().setExecutable(true)).isTrue();
    return script;
  }
}
