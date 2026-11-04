package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.callbacks.Callback;
import io.swagger.v3.oas.models.links.Link;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * US-29-02 (#372): the parts of the document whose order comes from classpath scanning are sorted;
 * the parts whose order is the code's own are kept.
 */
class ApiDocumentOrderTest {

  @Test
  void pathsResponsesTagsAndComponentsAreSortedByName() {
    ApiResponses responses = new ApiResponses();
    responses.addApiResponse("default", new ApiResponse().description("problem"));
    responses.addApiResponse("404", new ApiResponse().description("not found"));
    responses.addApiResponse("200", new ApiResponse().description("ok"));
    Paths paths = new Paths();
    paths.addPathItem("/b", new PathItem().get(new Operation().responses(responses)));
    paths.addPathItem("/a", new PathItem().post(new Operation()));
    paths.addExtension("x-kept", true);
    OpenAPI openApi =
        new OpenAPI()
            .paths(paths)
            .tags(
                new ArrayList<>(List.of(new Tag().name("Portfolios"), new Tag().name("Accounts"))))
            .components(
                new Components()
                    .addSchemas("Zeta", new StringSchema())
                    .addSchemas("Alpha", new StringSchema())
                    .addResponses("Z", new ApiResponse())
                    .addResponses("A", new ApiResponse()));

    ApiDocumentOrder.apply(openApi);

    assertThat(openApi.getPaths().keySet()).containsExactly("/a", "/b");
    assertThat(openApi.getPaths().getExtensions()).containsEntry("x-kept", true);
    assertThat(openApi.getPaths().get("/b").getGet().getResponses().keySet())
        .containsExactly("200", "404", "default");
    assertThat(openApi.getTags())
        .extracting(Tag::getName)
        .containsExactly("Accounts", "Portfolios");
    assertThat(openApi.getComponents().getSchemas().keySet()).containsExactly("Alpha", "Zeta");
    assertThat(openApi.getComponents().getResponses().keySet()).containsExactly("A", "Z");
  }

  @Test
  void theOtherSectionsWebhooksAndExtensionsAreSortedToo() {
    ApiResponses responses = new ApiResponses();
    responses.addApiResponse("default", new ApiResponse());
    responses.addApiResponse("200", new ApiResponse());
    OpenAPI openApi =
        new OpenAPI()
            .addWebhooks("zeta", new PathItem().post(new Operation().responses(responses)))
            .addWebhooks("alpha", new PathItem())
            .components(
                new Components()
                    .addLinks("Z", new Link())
                    .addLinks("A", new Link())
                    .addCallbacks("Z", new Callback())
                    .addCallbacks("A", new Callback())
                    .addPathItem("Z", new PathItem())
                    .addPathItem("A", new PathItem()));
    openApi.addExtension("x-zeta", 1);
    openApi.addExtension("x-alpha", 2);

    ApiDocumentOrder.apply(openApi);

    assertThat(openApi.getWebhooks().keySet()).containsExactly("alpha", "zeta");
    assertThat(openApi.getWebhooks().get("zeta").getPost().getResponses().keySet())
        .containsExactly("200", "default");
    assertThat(openApi.getExtensions().keySet()).containsExactly("x-alpha", "x-zeta");
    assertThat(openApi.getComponents().getLinks().keySet()).containsExactly("A", "Z");
    assertThat(openApi.getComponents().getCallbacks().keySet()).containsExactly("A", "Z");
    assertThat(openApi.getComponents().getPathItems().keySet()).containsExactly("A", "Z");
  }

  @Test
  void aTagWithoutANameGoesLastInsteadOfFailingTheDocument() {
    OpenAPI openApi =
        new OpenAPI()
            .tags(new ArrayList<>(List.of(new Tag(), new Tag().name("B"), new Tag().name("A"))));

    ApiDocumentOrder.apply(openApi);

    assertThat(openApi.getTags()).extracting(Tag::getName).containsExactly("A", "B", null);
  }

  @Test
  void aSchemasPropertiesKeepTheirDeclarationOrder() {
    Schema<?> record =
        new ObjectSchema()
            .addProperty("name", new StringSchema())
            .addProperty("balance", new StringSchema())
            .addProperty("asOf", new StringSchema());
    OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("Row", record));

    ApiDocumentOrder.apply(openApi);

    Map<String, Schema> properties =
        openApi.getComponents().getSchemas().get("Row").getProperties();
    assertThat(properties.keySet()).containsExactly("name", "balance", "asOf");
  }

  @Test
  void missingPartsStayMissing() {
    OpenAPI empty = new OpenAPI();

    ApiDocumentOrder.apply(empty);

    assertThat(empty.getPaths()).isNull();
    assertThat(empty.getTags()).isNull();
    assertThat(empty.getComponents()).isNull();

    OpenAPI bare = new OpenAPI().components(new Components());
    bare.setPaths(new Paths().addPathItem("/x", new PathItem().get(new Operation())));

    ApiDocumentOrder.apply(bare);

    assertThat(bare.getComponents().getSchemas()).isNull();
    assertThat(bare.getPaths().get("/x").getGet().getResponses()).isNull();
  }
}
