package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.links.Link;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Encoding;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

/**
 * US-29-02 (#372): what {@link ApiDocumentationConfig} adds to the contract beyond springdoc - the
 * declared success statuses in place of the generated 200, and the bearer token every operation
 * outside the public set requires.
 */
class ApiDocumentationContractTest {

  private final ApiDocumentationConfig config = new ApiDocumentationConfig();

  @Test
  void eachDeclaredStatusIsItsOwnCopyOfTheGeneratedResponse() throws NoSuchMethodException {
    Operation operation = config.successStatusResponses().customize(generated(), handler("both"));

    ApiResponse created = operation.getResponses().get("201");
    ApiResponse ok = operation.getResponses().get("200");
    assertThat(created.getDescription()).isEqualTo("Created");
    assertThat(ok.getDescription()).isEqualTo("OK");
    for (ApiResponse response : new ApiResponse[] {created, ok}) {
      assertThat(response.getHeaders()).containsKey("ETag");
      assertThat(response.getLinks()).containsKey("self");
      assertThat(response.getExtensions()).containsEntry("x-generated", true);
      MediaType json = response.getContent().get("application/json");
      assertThat(json.getSchema()).isInstanceOf(StringSchema.class);
      assertThat(json.getExample()).isEqualTo("example");
      assertThat(json.getExamples()).containsKey("one");
      assertThat(json.getEncoding()).containsKey("part");
      assertThat(json.getExtensions()).containsEntry("x-media", true);
    }

    created.getHeaders().remove("ETag");
    created.getContent().get("application/json").setExample("changed");
    created.getContent().remove("application/json");
    assertThat(ok.getHeaders()).containsKey("ETag");
    assertThat(ok.getContent().get("application/json").getExample()).isEqualTo("example");
  }

  @Test
  void aNoContentStatusKeepsTheHeadersButHasNoBody() throws NoSuchMethodException {
    Operation operation =
        config.successStatusResponses().customize(generated(), handler("noContent"));

    ApiResponse noContent = operation.getResponses().get("204");
    assertThat(operation.getResponses()).doesNotContainKey("200");
    assertThat(noContent.getContent()).isNull();
    assertThat(noContent.getHeaders()).containsKey("ETag");
  }

  @Test
  void aBareGeneratedResponseOrNoneAtAllStillDocumentsTheDeclaredStatus()
      throws NoSuchMethodException {
    Operation bare = new Operation().responses(new ApiResponses().addApiResponse("200", null));
    Operation none = new Operation();
    Operation minimal =
        new Operation()
            .responses(
                new ApiResponses()
                    .addApiResponse(
                        "200",
                        new ApiResponse()
                            .content(new Content().addMediaType("text/csv", new MediaType()))));

    config.successStatusResponses().customize(bare, handler("noContent"));
    config.successStatusResponses().customize(none, handler("noContent"));
    config.successStatusResponses().customize(minimal, handler("both"));

    assertThat(bare.getResponses().get("204").getDescription()).isEqualTo("No Content");
    assertThat(none.getResponses().get("204").getDescription()).isEqualTo("No Content");
    assertThat(minimal.getResponses().get("201").getHeaders()).isNull();
    assertThat(minimal.getResponses().get("201").getContent()).containsKey("text/csv");
    assertThat(minimal.getResponses().get("201").getContent().get("text/csv").getExampleSetFlag())
        .isFalse();
  }

  @Test
  void anUndeclaredHandlerKeepsTheGeneratedResponse() throws NoSuchMethodException {
    Operation operation = generated();
    ApiResponse before = operation.getResponses().get("200");

    config.successStatusResponses().customize(operation, handler("plain"));

    assertThat(operation.getResponses()).containsOnlyKeys("200");
    assertThat(operation.getResponses().get("200")).isSameAs(before);
  }

  @Test
  void everyOperationNeedsTheBearerTokenExceptSetUpAndAuthenticationAndAdminOnesMayBeForbidden() {
    OpenAPI openApi =
        new OpenAPI()
            .paths(
                new Paths()
                    .addPathItem("/api/v1/accounts", new PathItem().get(new Operation()))
                    .addPathItem("/api/v1/auth/login", new PathItem().post(new Operation()))
                    .addPathItem(
                        "/api/v1/setup/administrator", new PathItem().post(new Operation()))
                    .addPathItem("/api/v1/authors", new PathItem().get(new Operation()))
                    .addPathItem("/api/v1/admin/users", new PathItem().get(new Operation())));

    config.apiWireConventions().customise(openApi);

    SecurityScheme bearer = openApi.getComponents().getSecuritySchemes().get("bearerAuth");
    assertThat(bearer.getType()).isEqualTo(SecurityScheme.Type.HTTP);
    assertThat(bearer.getScheme()).isEqualTo("bearer");
    assertThat(bearer.getBearerFormat()).isEqualTo("JWT");
    assertThat(openApi.getSecurity()).hasSize(1);
    assertThat(openApi.getSecurity().get(0)).containsOnlyKeys("bearerAuth");

    Operation accounts = openApi.getPaths().get("/api/v1/accounts").getGet();
    assertThat(accounts.getSecurity()).isNull();
    assertThat(accounts.getResponses()).containsKey("401").doesNotContainKey("403");
    assertThat(openApi.getPaths().get("/api/v1/admin/users").getGet().getResponses())
        .containsKeys("401", "403");
    // A path only starting like a public one is not public.
    assertThat(openApi.getPaths().get("/api/v1/authors").getGet().getResponses())
        .containsKey("401");
    for (String open : new String[] {"/api/v1/auth/login", "/api/v1/setup/administrator"}) {
      Operation operation = openApi.getPaths().get(open).getPost();
      assertThat(operation.getSecurity()).as(open).isEmpty();
      assertThat(operation.getResponses()).as(open).doesNotContainKey("401");
    }
  }

  private static Operation generated() {
    MediaType json =
        new MediaType()
            .schema(new StringSchema())
            .example("example")
            .examples(Map.of("one", new Example().value("1")))
            .encoding(Map.of("part", new Encoding()));
    json.addExtension("x-media", true);
    ApiResponse ok =
        new ApiResponse()
            .description("OK")
            .content(new Content().addMediaType("application/json", json))
            .addHeaderObject("ETag", new Header())
            .link("self", new Link());
    ok.addExtension("x-generated", true);
    return new Operation().responses(new ApiResponses().addApiResponse("200", ok));
  }

  private static HandlerMethod handler(String name) throws NoSuchMethodException {
    return new HandlerMethod(new Endpoints(), Endpoints.class.getDeclaredMethod(name));
  }

  @SuppressWarnings("unused")
  static final class Endpoints {

    @SuccessStatus({HttpStatus.CREATED, HttpStatus.OK})
    void both() {}

    @SuccessStatus(HttpStatus.NO_CONTENT)
    void noContent() {}

    void plain() {}
  }
}
