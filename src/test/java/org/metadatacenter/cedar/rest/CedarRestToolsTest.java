package org.metadatacenter.cedar.rest;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the CRUD + validate tools against a fake {@link CedarHttp} — no live CEDAR server. Covers
 * request construction (path, method, body), the create {@code @id}-null rule, IRI URL-encoding,
 * the delete confirmation, error surfacing, and validate resource-type detection.
 */
final class CedarRestToolsTest
{
  private static final String TEMPLATE_TYPE_IRI = "https://schema.metadatacenter.org/core/Template";

  /** Records the last request and returns a canned response. */
  static final class FakeHttp implements CedarHttp
  {
    final int status;
    final String responseBody;
    String method, path, body;

    FakeHttp(int status, String responseBody) { this.status = status; this.responseBody = responseBody; }

    @Override public CedarResponse request(String method, String pathAndQuery, String jsonBody)
    {
      this.method = method;
      this.path = pathAndQuery;
      this.body = jsonBody;
      return new CedarResponse(status, responseBody);
    }
  }

  /**
   * Answers a template on the {@code GET /templates/...} (the schema:isBasedOn lookup) and a
   * separate canned response on the write; records the write method/path/body. A null
   * {@code templateJson} makes the template fetch 404, exercising the unreachable-template path.
   */
  static final class RoutingHttp implements CedarHttp
  {
    private final String templateJson;
    private final int writeStatus;
    private final String writeBody;
    String writeMethod, writePath, sentBody;
    boolean templateFetched;

    RoutingHttp(String templateJson, int writeStatus, String writeBody)
    {
      this.templateJson = templateJson;
      this.writeStatus = writeStatus;
      this.writeBody = writeBody;
    }

    @Override public CedarResponse request(String method, String pathAndQuery, String jsonBody)
    {
      if (method.equals("GET") && pathAndQuery.startsWith("/templates/")) {
        templateFetched = true;
        return templateJson == null ? new CedarResponse(404, "{}") : new CedarResponse(200, templateJson);
      }
      writeMethod = method;
      writePath = pathAndQuery;
      sentBody = jsonBody;
      return new CedarResponse(writeStatus, writeBody);
    }
  }

  @Test void registers_sixteen_crud_tools_plus_validate()
  {
    var names = ArtifactCrudTools.all(new FakeHttp(200, "{}")).stream().map(rt -> rt.tool().name()).toList();
    assertEquals(16, names.size(), "4 ops x 4 types; got " + names);
    for (String op : List.of("get", "create", "update", "delete"))
      for (String noun : List.of("template", "element", "field", "instance"))
        assertTrue(names.contains(op + "_" + noun), "missing " + op + "_" + noun + "; got " + names);
    assertEquals("validate_artifact", ValidateArtifactTool.create(new FakeHttp(200, "{}")).tool().name());
  }

  @Test void create_nulls_top_level_id_and_posts_to_collection()
  {
    FakeHttp http = new FakeHttp(201, "{}");
    invoke(http, "create_template", Map.of("artifact",
        "{\"@type\":\"" + TEMPLATE_TYPE_IRI + "\",\"schema:name\":\"Demo\","
            + "\"@id\":\"https://repo.metadatacenter.org/templates/minted-local\"}"));

    assertEquals("POST", http.method);
    assertEquals("/templates", http.path);
    assertTrue(http.body.contains("\"@id\":null"),
        "create must null the top-level @id so the server assigns one; got: " + http.body);
    assertFalse(http.body.contains("minted-local"),
        "the caller's @id must be overwritten with null; got: " + http.body);
  }

  @Test void create_accepts_yaml_and_posts_canonical_json()
  {
    FakeHttp http = new FakeHttp(201, "{}");
    McpSchema.CallToolResult result = invoke(http, "create_template",
        Map.of("artifact", "type: template\nname: Demo\n"));

    assertFalse(result.isError(), "YAML input must be accepted and converted; got: " + text(result));
    assertEquals("POST", http.method);
    assertEquals("/templates", http.path);
    assertTrue(http.body.contains(TEMPLATE_TYPE_IRI),
        "YAML must be converted to canonical CEDAR JSON before sending; got: " + http.body);
    assertTrue(http.body.contains("\"@id\":null"),
        "create must still null the top-level @id; got: " + http.body);
  }

  @Test void get_url_encodes_the_iri_into_the_path()
  {
    FakeHttp http = new FakeHttp(200, "{}");
    invoke(http, "get_template", Map.of("id", "https://repo.metadatacenter.org/templates/abc"));

    assertEquals("GET", http.method);
    assertEquals("/templates/https%3A%2F%2Frepo.metadatacenter.org%2Ftemplates%2Fabc", http.path);
  }

  @Test void delete_confirms_on_204()
  {
    FakeHttp http = new FakeHttp(204, "");
    McpSchema.CallToolResult result = invoke(http, "delete_template",
        Map.of("id", "https://repo.metadatacenter.org/templates/abc"));

    assertEquals("DELETE", http.method);
    assertFalse(result.isError());
    assertTrue(text(result).contains("Deleted template"), "got: " + text(result));
  }

  @Test void non_2xx_surfaces_as_error_with_status_and_body()
  {
    McpSchema.CallToolResult result = invoke(new FakeHttp(404, "{\"errorKey\":\"notFound\"}"),
        "get_template", Map.of("id", "https://repo.metadatacenter.org/templates/missing"));

    assertTrue(result.isError(), "a 404 must be an error result");
    assertTrue(text(result).contains("404") && text(result).contains("notFound"),
        "error should carry status and server body; got: " + text(result));
  }

  @Test void get_returns_yaml_by_default()
  {
    // A full canonical template the model reader can re-read: round-trip a compact-YAML template
    // through the codec to canonical JSON, hand that back as the server body, and expect YAML out.
    String canonicalJson = ArtifactCodec.toObjectNode("type: template\nname: Demo\n").toString();

    McpSchema.CallToolResult result = invoke(new FakeHttp(200, canonicalJson),
        "get_template", Map.of("id", "https://repo.metadatacenter.org/templates/demo"));

    assertFalse(result.isError(), text(result));
    assertTrue(text(result).contains("type: template") && text(result).contains("name: Demo"),
        "default output should be YAML; got:\n" + text(result));
    assertFalse(text(result).contains("\"@type\""), "default must not be JSON; got:\n" + text(result));
  }

  @Test void get_returns_pretty_json_when_format_is_json()
  {
    String templateJson = "{\"@type\":\"" + TEMPLATE_TYPE_IRI + "\","
        + "\"@id\":\"https://repo.metadatacenter.org/templates/demo\",\"schema:name\":\"Demo\"}";

    McpSchema.CallToolResult result = invoke(new FakeHttp(200, templateJson),
        "get_template",
        Map.of("id", "https://repo.metadatacenter.org/templates/demo", "format", "json"));

    assertFalse(result.isError(), text(result));
    // format: json — the @id and name survive and it pretty-prints over lines.
    assertTrue(text(result).contains("\"@id\" : \"https://repo.metadatacenter.org/templates/demo\""),
        "response should be pretty-printed JSON; got:\n" + text(result));
    assertTrue(text(result).contains("\"schema:name\" : \"Demo\""), text(result));
  }

  @Test void validate_detects_resource_type_from_at_type()
  {
    FakeHttp http = new FakeHttp(200, "{\"validates\":true,\"warnings\":[],\"errors\":[]}");
    String templateJson = "{\"@type\":\"" + TEMPLATE_TYPE_IRI + "\",\"schema:name\":\"X\"}";

    McpSchema.CallToolResult result = ValidateArtifactTool.create(http).handler()
        .apply(null, new McpSchema.CallToolRequest("validate_artifact", Map.of("artifact", templateJson)));

    assertFalse(result.isError(), text(result));
    assertEquals("POST", http.method);
    assertEquals("/command/validate?resource_type=template", http.path);
    assertEquals(templateJson, http.body, "JSON should be validated as-is");
  }

  @Test void create_requires_artifact()
  {
    McpSchema.CallToolResult result = invoke(new FakeHttp(201, "{}"), "create_field", Map.of());
    assertTrue(result.isError());
    assertTrue(text(result).contains("artifact"));
  }

  @Test void create_instance_inflates_sparse_yaml_against_its_template()
  {
    RoutingHttp http = new RoutingHttp(studyTemplateJson(), 201, "{}");

    McpSchema.CallToolResult result = invoke(http, "create_instance",
        Map.of("artifact", SPARSE_INSTANCE_YAML));

    assertFalse(result.isError(), text(result));
    assertTrue(http.templateFetched, "the template should be fetched (via schema:isBasedOn) to inflate");
    assertEquals("POST", http.writeMethod);
    // The 'Notes' field the sparse instance omitted is materialized before the body is sent.
    assertTrue(http.sentBody.contains("Notes"),
        "the omitted field must be materialized before upload; sent: " + http.sentBody);
    assertTrue(http.sentBody.contains("\"@id\":null"), "create must still null the top-level @id");
  }

  @Test void create_instance_uploads_as_is_with_a_note_when_template_unreachable()
  {
    // Template GET 404s, so inflation is skipped; the server then rejects the sparse instance.
    RoutingHttp http = new RoutingHttp(null, 400, "{\"errorKey\":\"incomplete\"}");

    McpSchema.CallToolResult result = invoke(http, "create_instance",
        Map.of("artifact", SPARSE_INSTANCE_YAML));

    assertTrue(result.isError());
    assertTrue(text(result).contains("could not fetch the template"),
        "a degraded upload should explain the skipped inflation; got: " + text(result));
  }

  // helpers

  private static final String SPARSE_INSTANCE_YAML =
      "type: instance\nname: Study Instance\n"
          + "isBasedOn: https://repo.metadatacenter.org/templates/T1\n";

  /** A canonical one-field template, rendered to JSON the way the server would serve it. */
  private static String studyTemplateJson()
  {
    TextField notes = TextField.builder().withName("Notes").build();
    TemplateSchemaArtifact template = TemplateSchemaArtifact.builder().withName("Study")
        .withJsonLdId(URI.create("https://repo.metadatacenter.org/templates/T1"))
        .withFieldSchema(notes).build();
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(template).toString();
  }

  private static McpSchema.CallToolResult invoke(CedarHttp http, String toolName, Map<String, Object> args)
  {
    for (ArtifactCrudTools.RegisteredTool rt : ArtifactCrudTools.all(http))
      if (rt.tool().name().equals(toolName))
        return rt.handler().apply(null, new McpSchema.CallToolRequest(toolName, args));
    throw new IllegalArgumentException("no such tool: " + toolName);
  }

  private static String text(McpSchema.CallToolResult result)
  {
    assertNotNull(result.content());
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}
