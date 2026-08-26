package org.metadatacenter.cedar.rest;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the CRUD + validate tools against a fake {@link CedarHttp} — no live CEDAR server. Covers
 * request construction (path, method, body, negotiated formats), the create identifier rule, IRI
 * URL-encoding, the re-read after an update, the delete confirmation, error surfacing, and validate
 * kind detection.
 */
final class CedarRestToolsTest
{
  private static final String TEMPLATE_TYPE_IRI = "https://schema.metadatacenter.org/core/Template";
  private static final String TEMPLATE_IRI = "https://repo.metadatacenter.org/templates/T1";

  /** One request the fake transport saw. */
  record Call(String method, String path, String body, ArtifactFormat bodyFormat, ArtifactFormat accept) {}

  /** Records every request and returns a canned response to each. */
  static class FakeHttp implements CedarHttp
  {
    final List<Call> calls = new ArrayList<>();
    private final int status;
    private final String responseBody;

    FakeHttp(int status, String responseBody) { this.status = status; this.responseBody = responseBody; }

    @Override public CedarResponse request(String method, String pathAndQuery, String body,
        ArtifactFormat bodyFormat, ArtifactFormat accept)
    {
      calls.add(new Call(method, pathAndQuery, body, bodyFormat, accept));
      return respond(method, pathAndQuery);
    }

    CedarResponse respond(String method, String pathAndQuery)
    {
      return new CedarResponse(status, responseBody);
    }

    Call last() { return calls.get(calls.size() - 1); }

    Call first() { return calls.get(0); }
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

  @Test void instance_create_update_and_validate_share_the_same_value_vocabulary()
  {
    FakeHttp http = new FakeHttp(200, "{}");
    String create = tool(http, "create_instance").tool().description();
    String update = tool(http, "update_instance").tool().description();
    String validate = ValidateArtifactTool.create(http).tool().description();

    for (String description : List.of(create, update, validate)) {
      assertTrue(description.endsWith(ArtifactCrudTools.INSTANCE_VALUE_VOCABULARY), description);
      assertTrue(description.contains("Custom Properties:\n    color:\n      value: red"), description);
      assertFalse(description.contains("no compact-form spelling"), description);
      assertFalse(description.contains("Omit them"), description);
    }
  }

  @Test void create_sends_the_yaml_unchanged_and_asks_for_yaml_back()
  {
    FakeHttp http = new FakeHttp(201, "type: template\nname: Demo\n");
    String yaml = "type: template\nname: Demo\n";

    McpSchema.CallToolResult result = invoke(http, "create_template", Map.of("artifact", yaml));

    assertFalse(result.isError(), text(result));
    assertEquals("POST", http.last().method());
    assertEquals("/templates", http.last().path());
    assertEquals(yaml, http.last().body(), "YAML must travel to the server unaltered");
    assertEquals(ArtifactFormat.YAML, http.last().bodyFormat());
    assertEquals(ArtifactFormat.YAML, http.last().accept());
  }

  @Test void create_strips_an_identifier_the_caller_supplied()
  {
    FakeHttp http = new FakeHttp(201, "type: template\n");
    invoke(http, "create_template", Map.of("artifact",
        "type: template\nname: Demo\nid: " + TEMPLATE_IRI + "\n"));

    assertFalse(http.last().body().contains(TEMPLATE_IRI),
        "the server mints identifiers; a supplied one must not be sent: " + http.last().body());
    assertTrue(http.last().body().contains("Demo"), "the rest of the artifact survives");
  }

  @Test void create_strips_the_json_ld_identifier_too()
  {
    FakeHttp http = new FakeHttp(201, "{}");
    invoke(http, "create_template", Map.of("artifact",
        "{\"@type\":\"" + TEMPLATE_TYPE_IRI + "\",\"schema:name\":\"Demo\","
            + "\"@id\":\"" + TEMPLATE_IRI + "\"}"));

    assertEquals(ArtifactFormat.JSON, http.last().bodyFormat(), "JSON in, JSON on the wire");
    assertFalse(http.last().body().contains(TEMPLATE_IRI), "got: " + http.last().body());
    assertTrue(http.last().body().contains("\"@id\":null"),
        "JSON says 'mint me one' with an explicit null; got: " + http.last().body());
  }

  @Test void get_returns_the_server_body_verbatim()
  {
    String yaml = "type: template\nname: Demo\n";
    FakeHttp http = new FakeHttp(200, yaml);

    McpSchema.CallToolResult result = invoke(http, "get_template", Map.of("id", TEMPLATE_IRI));

    assertFalse(result.isError(), text(result));
    assertEquals(yaml, text(result), "the server's YAML is returned as it arrived");
    assertEquals("GET", http.last().method());
    assertEquals(ArtifactFormat.YAML, http.last().accept());
    assertNull(http.last().body());
  }

  @Test void get_asks_for_json_when_the_caller_does()
  {
    FakeHttp http = new FakeHttp(200, "{\"@id\":\"x\"}");
    invoke(http, "get_template", Map.of("id", TEMPLATE_IRI, "format", "json"));

    assertEquals(ArtifactFormat.JSON, http.last().accept());
  }

  @Test void get_url_encodes_the_iri_into_the_path()
  {
    FakeHttp http = new FakeHttp(200, "type: template\n");
    invoke(http, "get_template", Map.of("id", "https://repo.metadatacenter.org/templates/abc"));

    assertEquals("/templates/https%3A%2F%2Frepo.metadatacenter.org%2Ftemplates%2Fabc", http.last().path());
  }

  @Test void update_puts_the_artifact_then_returns_the_re_read_one()
  {
    // The PUT answers with the folder record; the tool answers with the artifact it re-reads.
    FakeHttp http = new FakeHttp(200, "type: template\nname: Demo\n") {
      @Override CedarResponse respond(String method, String pathAndQuery)
      {
        return method.equals("PUT")
            ? new CedarResponse(200, "{\"resourceType\":\"template\",\"pathInfo\":[]}")
            : super.respond(method, pathAndQuery);
      }
    };

    McpSchema.CallToolResult result = invoke(http, "update_template", Map.of(
        "id", TEMPLATE_IRI, "artifact", "type: template\nname: Demo\nid: " + TEMPLATE_IRI + "\n"));

    assertFalse(result.isError(), text(result));
    assertEquals(2, http.calls.size(), "a PUT and the re-read that follows it");
    assertEquals("PUT", http.first().method());
    assertTrue(http.first().body().contains(TEMPLATE_IRI), "update keeps the identifier");
    assertEquals(ArtifactFormat.JSON, http.first().accept(),
        "the folder record a PUT answers with has no YAML form");
    assertEquals("GET", http.last().method());
    assertEquals(ArtifactFormat.YAML, http.last().accept());
    assertEquals("type: template\nname: Demo\n", text(result));
  }

  @Test void update_falls_back_to_the_write_response_when_the_re_read_fails()
  {
    FakeHttp http = new FakeHttp(404, "{\"errorKey\":\"notFound\"}") {
      @Override CedarResponse respond(String method, String pathAndQuery)
      {
        return method.equals("PUT") ? new CedarResponse(200, "{\"resourceType\":\"template\"}")
            : super.respond(method, pathAndQuery);
      }
    };

    McpSchema.CallToolResult result = invoke(http, "update_template", Map.of(
        "id", TEMPLATE_IRI, "artifact", "type: template\nname: Demo\nid: " + TEMPLATE_IRI + "\n"));

    assertFalse(result.isError(), "the write succeeded, so the result is not an error");
    assertTrue(text(result).contains("was updated, but re-reading it returned HTTP 404"), text(result));
  }

  @Test void delete_confirms_on_204()
  {
    FakeHttp http = new FakeHttp(204, "");
    McpSchema.CallToolResult result = invoke(http, "delete_template", Map.of("id", TEMPLATE_IRI));

    assertEquals("DELETE", http.last().method());
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

  @Test void missing_api_key_is_diagnosed_before_a_live_request()
  {
    CedarHttp http = new DefaultCedarHttp(new CedarConfig("http://127.0.0.1:1", ""));

    McpSchema.CallToolResult result = invoke(http, "get_template", Map.of("id", TEMPLATE_IRI));

    assertTrue(result.isError());
    assertTrue(text(result).contains("CEDAR_API_KEY"), text(result));
    assertTrue(text(result).contains("restart the MCP server"), text(result));
  }

  @Test void validate_reads_the_kind_from_the_yaml_type()
  {
    FakeHttp http = new FakeHttp(200, "{\"validates\":\"true\",\"warnings\":[],\"errors\":[]}");
    String yaml = "type: element\nname: Address\n";

    McpSchema.CallToolResult result = ValidateArtifactTool.create(http).handler()
        .apply(null, new McpSchema.CallToolRequest("validate_artifact", Map.of("artifact", yaml)));

    assertFalse(result.isError(), text(result));
    assertEquals("/command/validate?resource_type=element", http.last().path());
    assertEquals(yaml, http.last().body(), "the YAML is validated as written");
    assertEquals(ArtifactFormat.YAML, http.last().bodyFormat());
  }

  @Test void validate_reads_the_kind_from_the_json_ld_type()
  {
    FakeHttp http = new FakeHttp(200, "{\"validates\":\"true\"}");
    String json = "{\"@type\":\"" + TEMPLATE_TYPE_IRI + "\",\"schema:name\":\"X\"}";

    McpSchema.CallToolResult result = ValidateArtifactTool.create(http).handler()
        .apply(null, new McpSchema.CallToolRequest("validate_artifact", Map.of("artifact", json)));

    assertFalse(result.isError(), text(result));
    assertEquals("/command/validate?resource_type=template", http.last().path());
    assertEquals(json, http.last().body(), "JSON should be validated as-is");
    assertEquals(ArtifactFormat.JSON, http.last().bodyFormat());
  }

  @Test void create_requires_artifact()
  {
    McpSchema.CallToolResult result = invoke(new FakeHttp(201, "{}"), "create_field", Map.of());
    assertTrue(result.isError());
    assertTrue(text(result).contains("artifact"));
  }

  @Test void create_instance_sends_the_sparse_yaml_as_it_was_written()
  {
    // The server completes it against its template; nothing is fetched or converted here.
    FakeHttp http = new FakeHttp(201, "type: instance\n");

    McpSchema.CallToolResult result = invoke(http, "create_instance",
        Map.of("artifact", SPARSE_INSTANCE_YAML));

    assertFalse(result.isError(), text(result));
    assertEquals(1, http.calls.size(), "no template lookup: the write is the only call");
    assertEquals("POST", http.last().method());
    assertEquals("/template-instances", http.last().path());
    assertEquals(SPARSE_INSTANCE_YAML, http.last().body(), "the YAML travels unaltered");
    assertEquals(ArtifactFormat.YAML, http.last().bodyFormat());
  }

  // helpers

  private static final String SPARSE_INSTANCE_YAML =
      "type: instance\nname: Study Instance\nisBasedOn: " + TEMPLATE_IRI + "\n";

  private static McpSchema.CallToolResult invoke(CedarHttp http, String toolName, Map<String, Object> args)
  {
    ArtifactCrudTools.RegisteredTool tool = tool(http, toolName);
    return tool.handler().apply(null, new McpSchema.CallToolRequest(toolName, args));
  }

  private static ArtifactCrudTools.RegisteredTool tool(CedarHttp http, String toolName)
  {
    return ArtifactCrudTools.all(http).stream()
        .filter(rt -> rt.tool().name().equals(toolName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no such tool: " + toolName));
  }

  private static String text(McpSchema.CallToolResult result)
  {
    assertNotNull(result.content());
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}
