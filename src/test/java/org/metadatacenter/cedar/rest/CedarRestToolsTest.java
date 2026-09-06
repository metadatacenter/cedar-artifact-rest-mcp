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
  private static final String OTHER_IRI = "https://repo.metadatacenter.org/templates/T2";

  /** One request the fake transport saw. */
  record Call(String method, String path, String body, ArtifactFormat bodyFormat, ArtifactFormat accept,
      String ifMatch) {}

  /** The entity tag every fake read hands back, for a write to assert against. */
  static final String ETAG = "\"7-yaml-compact\"";

  /** Records every request and returns a canned response to each. */
  static class FakeHttp implements CedarHttp
  {
    final List<Call> calls = new ArrayList<>();
    private final int status;
    private final String responseBody;

    FakeHttp(int status, String responseBody) { this.status = status; this.responseBody = responseBody; }

    @Override public CedarResponse request(String method, String pathAndQuery, String body,
        ArtifactFormat bodyFormat, ArtifactFormat accept, String ifMatch)
    {
      calls.add(new Call(method, pathAndQuery, body, bodyFormat, accept, ifMatch));
      return respond(method, pathAndQuery);
    }

    CedarResponse respond(String method, String pathAndQuery)
    {
      return new CedarResponse(status, responseBody, ETAG);
    }

    /** The write in a sequence that also reads for the precondition. */
    Call write()
    {
      return calls.stream().filter(c -> !c.method().equals("GET")).findFirst().orElseThrow();
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

  @Test void create_posts_then_returns_the_artifact_re_read_in_compact_form()
  {
    // The POST answers with the created artifact, but CEDAR takes no compact parameter on a write,
    // so the tool answers with the compact copy it reads back.
    String compact = "type: template\nname: Demo\nid: " + TEMPLATE_IRI + "\n";
    FakeHttp http = new FakeHttp(200, compact) {
      @Override CedarResponse respond(String method, String pathAndQuery)
      {
        return method.equals("POST")
            ? new CedarResponse(201, "type: template\nname: Demo\nid: " + TEMPLATE_IRI
                + "\nstatus: draft\nmodelVersion: 1.6.0\n")
            : super.respond(method, pathAndQuery);
      }
    };

    McpSchema.CallToolResult result = invoke(http, "create_template",
        Map.of("artifact", "type: template\nname: Demo\n"));

    assertFalse(result.isError(), text(result));
    assertEquals(2, http.calls.size(), "a POST and the re-read that follows it");
    assertEquals("POST", http.first().method());
    assertEquals("GET", http.last().method());
    assertTrue(http.last().path().endsWith("?compact=true"),
        "the re-read exists to get the compact form, so it must name it");
    assertEquals(compact, text(result));
  }

  @Test void create_asking_for_json_does_not_re_read()
  {
    FakeHttp http = new FakeHttp(201, "{\"@id\":\"" + TEMPLATE_IRI + "\"}");

    invoke(http, "create_template", Map.of(
        "artifact", "type: template\nname: Demo\n", "format", "json"));

    assertEquals(1, http.calls.size(), "JSON has one form; there is nothing to re-read for");
    assertEquals("POST", http.last().method());
  }

  @Test void create_falls_back_to_the_write_response_when_the_re_read_fails()
  {
    FakeHttp http = new FakeHttp(404, "{\"errorKey\":\"notFound\"}") {
      @Override CedarResponse respond(String method, String pathAndQuery)
      {
        return method.equals("POST")
            ? new CedarResponse(201, "type: template\nname: Demo\nid: " + TEMPLATE_IRI + "\n")
            : super.respond(method, pathAndQuery);
      }
    };

    McpSchema.CallToolResult result = invoke(http, "create_template",
        Map.of("artifact", "type: template\nname: Demo\n"));

    assertFalse(result.isError(), "the artifact exists; a failed re-read is not a failed create");
    assertTrue(text(result).contains(TEMPLATE_IRI), "the created artifact still comes back");
    assertTrue(text(result).contains("expanded form"),
        "the caller must be told which form it got: " + text(result));
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

    String path = http.last().path();
    String withoutQuery = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
    assertEquals("/templates/https%3A%2F%2Frepo.metadatacenter.org%2Ftemplates%2Fabc", withoutQuery);
  }

  @Test void get_asks_for_the_compact_yaml_representation()
  {
    FakeHttp http = new FakeHttp(200, "type: template\n");
    invoke(http, "get_template", Map.of("id", TEMPLATE_IRI));

    assertTrue(http.last().path().endsWith("?compact=true"),
        "a YAML read must name the compact representation, else CEDAR serves the expanded one");
  }

  @Test void get_asks_for_the_full_form_when_told_not_to_compact()
  {
    FakeHttp http = new FakeHttp(200, "type: template\n");

    invoke(http, "get_template", Map.of("id", TEMPLATE_IRI, "compact", false));

    assertFalse(http.last().path().contains("compact"),
        "CEDAR serves the compact form unless a read declines it, and an update needs the full one");
  }

  @Test void get_compacts_unless_the_caller_says_otherwise()
  {
    FakeHttp http = new FakeHttp(200, "type: template\n");

    invoke(http, "get_template", Map.of("id", TEMPLATE_IRI, "compact", true));

    assertTrue(http.last().path().endsWith("?compact=true"));
  }

  @Test void get_leaves_compact_off_when_it_asks_for_json()
  {
    FakeHttp http = new FakeHttp(200, "{\"@id\":\"x\"}");
    invoke(http, "get_template", Map.of("id", TEMPLATE_IRI, "format", "json"));

    assertFalse(http.last().path().contains("compact"),
        "JSON has one form and CEDAR takes no compact parameter for it");
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
    assertEquals(3, http.calls.size(), "a read for the precondition, the PUT, and the re-read");
    assertEquals("GET", http.first().method(), "the tag a write asserts against comes from a read");

    Call put = http.write();
    assertEquals("PUT", put.method());
    assertEquals(ETAG, put.ifMatch(), "CEDAR answers 428 to an update that asserts no revision");
    assertTrue(put.body().contains(TEMPLATE_IRI),
        "the artifact server refuses a PUT whose body carries no @id, so the identity is kept");
    assertEquals(ArtifactFormat.JSON, put.accept(),
        "the folder record a PUT answers with has no YAML form");

    assertEquals("GET", http.last().method());
    assertEquals(ArtifactFormat.YAML, http.last().accept());
    assertEquals("type: template\nname: Demo\n", text(result));
  }

  @Test void delete_asserts_the_revision_its_read_reported()
  {
    FakeHttp http = new FakeHttp(204, "");

    invoke(http, "delete_template", Map.of("id", TEMPLATE_IRI));

    assertEquals("GET", http.first().method(), "a delete reads first, for the tag");
    assertEquals("DELETE", http.last().method());
    assertEquals(ETAG, http.last().ifMatch(), "CEDAR answers 428 to a delete that asserts no revision");
  }

  @Test void update_refuses_a_body_naming_a_different_artifact()
  {
    FakeHttp http = new FakeHttp(200, "type: template\n");

    McpSchema.CallToolResult result = invoke(http, "update_template", Map.of(
        "id", TEMPLATE_IRI, "artifact", "type: template\nname: Demo\nid: " + OTHER_IRI + "\n"));

    assertTrue(result.isError());
    // The id is dropped before the body is sent, so nothing downstream could catch this.
    assertEquals(0, http.calls.size(), "a mismatch is refused before anything reaches the server");
    assertTrue(text(result).contains(OTHER_IRI), text(result));
  }

  @Test void update_surfaces_a_failed_precondition_read_rather_than_writing()
  {
    FakeHttp http = new FakeHttp(404, "{\"errorKey\":\"notFound\"}");

    McpSchema.CallToolResult result = invoke(http, "update_template", Map.of(
        "id", TEMPLATE_IRI, "artifact", "type: template\nname: Demo\n"));

    assertTrue(result.isError());
    assertEquals(1, http.calls.size(), "the read failed, so no write was attempted");
    assertEquals("GET", http.last().method());
  }

  @Test void update_falls_back_to_the_write_response_when_the_re_read_fails()
  {
    FakeHttp http = new FakeHttp(200, "type: template\nname: Demo\n") {
      private int gets = 0;

      @Override CedarResponse respond(String method, String pathAndQuery)
      {
        if (method.equals("PUT"))
          return new CedarResponse(200, "{\"resourceType\":\"template\"}");
        // The first read supplies the precondition and succeeds. The re-read after the write is the
        // one that fails, which is the case this covers.
        return ++gets == 1 ? super.respond(method, pathAndQuery)
            : new CedarResponse(404, "{\"errorKey\":\"notFound\"}");
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
