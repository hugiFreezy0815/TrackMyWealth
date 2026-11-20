package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

class DeprecationHeadersTest {

  private static final String NOTE = "https://example.org/migrate#old";

  record Item(
      String name,
      @ApiDeprecation(since = "2027-03-01", migration = NOTE, sunset = "2027-09-01")
          String oldName) {}

  record Plain(String name) {}

  @RestController
  static class ProbeController {

    @Operation(operationId = "getOld")
    @ApiDeprecation(since = "2027-02-01", migration = NOTE)
    @GetMapping("/old")
    Plain old() {
      return new Plain("x");
    }

    @GetMapping("/failing-old")
    @ApiDeprecation(since = "2027-02-01", migration = NOTE)
    Plain failingOld() {
      throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
    }

    @Operation(operationId = "createItem")
    @PostMapping("/items")
    Plain withOldField(@RequestBody Item item) {
      return new Plain(item.name());
    }

    @Operation(operationId = "getCurrent")
    @GetMapping("/current")
    Plain current() {
      return new Plain("x");
    }
  }

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ProbeController())
          .addInterceptors(new DeprecationHeaders(registry))
          .build();

  @Test
  void aDeprecatedOperationAnnouncesItselfWithoutASunsetYet() throws Exception {
    MockHttpServletResponse response = perform(get("/old"));

    assertThat(response.getStatus()).isEqualTo(200);
    // 2027-02-01T00:00:00Z
    assertThat(response.getHeader("Deprecation")).isEqualTo("@1801440000");
    assertThat(response.getHeaders(HttpHeaders.LINK))
        .containsExactly("<" + NOTE + ">; rel=\"deprecation\"; type=\"text/html\"");
    assertThat(response.getHeader("Sunset")).isNull();
  }

  @Test
  void anErrorResponseOfADeprecatedOperationCarriesTheHeadersToo() throws Exception {
    MockHttpServletResponse response = perform(get("/failing-old"));

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getHeader("Deprecation")).isEqualTo("@1801440000");
  }

  @Test
  void aDeprecatedRequestFieldAnnouncesItsSunset() throws Exception {
    MockHttpServletResponse response =
        perform(
            post("/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"a\",\"oldName\":\"b\"}"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Deprecation")).isEqualTo("@1803859200");
    assertThat(response.getHeader("Sunset")).isEqualTo("Wed, 01 Sep 2027 00:00:00 GMT");
    assertThat(response.getHeader(HttpHeaders.LINK)).startsWith("<" + NOTE + ">");
  }

  @Test
  void anOperationThatUsesNothingDeprecatedSendsNothing() throws Exception {
    MockHttpServletResponse response = perform(get("/current"));

    assertThat(response.getHeader("Deprecation")).isNull();
    assertThat(response.getHeader("Sunset")).isNull();
    assertThat(response.getHeader(HttpHeaders.LINK)).isNull();
    assertThat(registry.find(DeprecationHeaders.METRIC).counters()).isEmpty();
  }

  @Test
  void countsEachCallByOperationAndAppVersion() throws Exception {
    perform(get("/old").header(AppVersion.HEADER, "1.3.0"));
    perform(get("/old").header(AppVersion.HEADER, "1.3.0"));
    perform(get("/old").header(AppVersion.HEADER, "1.4.0"));
    perform(get("/old"));
    perform(get("/failing-old").header(AppVersion.HEADER, "1.4.0"));

    assertThat(count("getOld", "1.3.0")).isEqualTo(2);
    assertThat(count("getOld", "1.4.0")).isEqualTo(1);
    assertThat(count("getOld", DeprecationHeaders.NO_VERSION)).isEqualTo(1);
    // Without an operationId: the handler's name.
    assertThat(count("failingOld", "1.4.0")).isEqualTo(1);
  }

  @Test
  void countsAVersionWithSurroundingSpaceAsThatVersion() throws Exception {
    perform(get("/old").header(AppVersion.HEADER, " 1.4.0 "));

    assertThat(count("getOld", "1.4.0")).isEqualTo(1);
  }

  @Test
  void countsFurtherVersionsAsOtherOnceTheCapIsReachedSoTheTotalStaysComplete() throws Exception {
    for (int patch = 0; patch < DeprecationHeaders.MAX_COUNTED_APP_VERSIONS; patch++) {
      perform(get("/old").header(AppVersion.HEADER, "9.0." + patch));
    }
    perform(get("/old").header(AppVersion.HEADER, "1.4.0"));
    perform(get("/old").header(AppVersion.HEADER, "1.5.0"));
    perform(get("/old").header(AppVersion.HEADER, "9.0.0"));

    assertThat(count("getOld", DeprecationHeaders.OTHER_VERSION)).isEqualTo(2);
    assertThat(count("getOld", "1.4.0")).isZero();
    // A version kept apart before the cap is still counted as itself.
    assertThat(count("getOld", "9.0.0")).isEqualTo(2);
    assertThat(registry.find(DeprecationHeaders.METRIC).counters())
        .hasSize(DeprecationHeaders.MAX_COUNTED_APP_VERSIONS + 1);
    assertThat(
            registry.find(DeprecationHeaders.METRIC).counters().stream()
                .mapToDouble(Counter::count)
                .sum())
        .isEqualTo(DeprecationHeaders.MAX_COUNTED_APP_VERSIONS + 3);
  }

  @Test
  void leavesAHandlerThatIsNoControllerMethodAlone() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean proceed =
        new DeprecationHeaders(registry)
            .preHandle(new MockHttpServletRequest(), response, new Object());

    assertThat(proceed).isTrue();
    assertThat(response.getHeaderNames()).isEmpty();
  }

  @Test
  void formatsTheRfcValues() {
    assertThat(DeprecationHeaders.deprecationValue(LocalDate.of(1970, 1, 2))).isEqualTo("@86400");
    assertThat(DeprecationHeaders.sunsetValue(LocalDate.of(2027, 1, 5)))
        .isEqualTo("Tue, 05 Jan 2027 00:00:00 GMT");
    assertThat(DeprecationHeaders.linkValue(URI.create(NOTE)))
        .isEqualTo("<https://example.org/migrate#old>; rel=\"deprecation\"; type=\"text/html\"");
  }

  private double count(String operation, String version) {
    Counter counter =
        registry
            .find(DeprecationHeaders.METRIC)
            .tag(DeprecationHeaders.OPERATION_TAG, operation)
            .tag(DeprecationHeaders.APP_VERSION_TAG, version)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse();
  }
}
