package org.metadatacenter.cedar.rest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Builds the CRUD tools — {@code get / create / update / delete} for each {@link ArtifactType} —
 * against a {@link CedarHttp}. The four artifact kinds differ only by path segment and tool noun,
 * so the tools are generated rather than written out as 16 near-identical classes; the server
 * registers them in a loop.
 *
 * <p>Conventions: artifact IDs are IRIs, URL-encoded into the path. Artifact bodies are supplied
 * as YAML (the compact exchange form); JSON is also accepted on input. YAML is converted to
 * JSON via {@code cedar-artifact-library} before it's sent (the server now accepts YAML too).
 * Responses are rendered back to YAML by default, or as JSON when the caller
 * passes {@code format: json}. {@code create}
 * nulls the top-level {@code @id} so the server assigns one. Non-2xx responses surface the server's
 * status and body as an error result (errors are content).
 */
final class ArtifactCrudTools
{
  /** A built tool paired with its handler, ready to hand to {@code McpServer...toolCall}. */
  record RegisteredTool(
      McpSchema.Tool tool,
      BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {}

  private ArtifactCrudTools() {}

  static List<RegisteredTool> all(CedarHttp http)
  {
    List<RegisteredTool> tools = new ArrayList<>();
    for (ArtifactType type : ArtifactType.values()) {
      tools.add(getTool(type, http));
      tools.add(createTool(type, http));
      tools.add(updateTool(type, http));
      tools.add(deleteTool(type, http));
    }
    return tools;
  }

  // ---------------------------------------------------------------- get

  private static RegisteredTool getTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("id", idProperty(type));
    properties.put("format", formatProperty());

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("get_" + type.noun)
        .title("Fetch a CEDAR " + type.noun + " from the server")
        .description(
            "Fetches a CEDAR " + type.noun + " from the CEDAR server by its @id (IRI). Returns the "
                + "artifact as YAML (the compact exchange form — an order of magnitude smaller than "
                + "JSON and lossless), or as JSON only if you pass format: json. "
                + "Reproduce the returned artifact verbatim — do not drop id/@id lines or summarize."
                + authoringPointer(type))
        .inputSchema(schema(properties, List.of("id")))
        .build();

    BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (exchange, request) -> {
          Map<String, Object> args = args(request);
          String id = str(args, "id");
          if (id == null || id.isBlank())
            return error("id is required (the artifact's @id IRI)");
          CedarHttp.CedarResponse response;
          try {
            response = http.request("GET", idPath(type, id), null);
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return artifactResult(type, response, !wantsJson(args), null);
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- create

  private static RegisteredTool createTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", artifactProperty(type));
    properties.put("format", formatProperty());

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("create_" + type.noun)
        .title("Create a CEDAR " + type.noun + " on the server")
        .description(
            "Creates a new CEDAR " + type.noun + " on the CEDAR server (it is placed in your home "
                + "folder). The artifact's top-level @id is set to null on submission; the server "
                + "assigns the real @id and returns the created artifact as YAML (the compact "
                + "exchange form), or as JSON only if you pass format: json. WRITES to "
                + "the server. Supply the artifact inline as YAML (the compact form "
                + "cedar-artifact-mcp returns); JSON is also accepted. Pass it verbatim, "
                + "don't reformat." + instanceUploadHint(type) + noHandBuiltJsonLd()
                + instanceValueVocabulary(type))
        .inputSchema(schema(properties, List.of("artifact")))
        .build();

    BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (exchange, request) -> {
          Map<String, Object> args = args(request);
          String text = str(args, "artifact");
          if (text == null || text.isBlank())
            return error("artifact is required and must not be blank");
          ObjectNode body;
          try {
            body = ArtifactCodec.toObjectNode(text);
          } catch (RuntimeException e) {
            return error("artifact could not be parsed as JSON or YAML: " + e.getMessage());
          }
          // For an instance, inflate against its template (sparse YAML omits empty fields the
          // server requires); then null the @id so the server mints one.
          Prepared prepared = prepareInstanceBody(type, body, http);
          body = prepared.body();
          ArtifactCodec.nullifyTopLevelId(body);
          CedarHttp.CedarResponse response;
          try {
            response = http.request("POST", "/" + type.pathSegment, ArtifactCodec.compactJson(body));
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return artifactResult(type, response, !wantsJson(args), prepared.note());
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- update

  private static RegisteredTool updateTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("id", idProperty(type));
    properties.put("artifact", artifactProperty(type));
    properties.put("format", formatProperty());

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("update_" + type.noun)
        .title("Update a CEDAR " + type.noun + " on the server")
        .description(
            "Updates an existing CEDAR " + type.noun + " on the server (PUT) by its @id (IRI). The "
                + "@id in the artifact body must match the id argument. Returns the updated artifact "
                + "as YAML (the compact exchange form), or as JSON only if you pass "
                + "format: json. WRITES to the server. Supply the artifact inline as YAML (the "
                + "compact form cedar-artifact-mcp returns); JSON is also accepted. Pass it "
                + "verbatim, don't reformat." + instanceUploadHint(type) + noHandBuiltJsonLd()
                + instanceValueVocabulary(type))
        .inputSchema(schema(properties, List.of("id", "artifact")))
        .build();

    BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (exchange, request) -> {
          Map<String, Object> args = args(request);
          String id = str(args, "id");
          if (id == null || id.isBlank())
            return error("id is required (the artifact's @id IRI)");
          String text = str(args, "artifact");
          if (text == null || text.isBlank())
            return error("artifact is required and must not be blank");
          ObjectNode body;
          try {
            body = ArtifactCodec.toObjectNode(text);
          } catch (RuntimeException e) {
            return error("artifact could not be parsed as JSON or YAML: " + e.getMessage());
          }
          // For an instance, inflate against its template so the sparse YAML the caller passed
          // carries every property the server's validator requires.
          Prepared prepared = prepareInstanceBody(type, body, http);
          body = prepared.body();
          CedarHttp.CedarResponse response;
          try {
            response = http.request("PUT", idPath(type, id), ArtifactCodec.compactJson(body));
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return artifactResult(type, response, !wantsJson(args), prepared.note());
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- delete

  private static RegisteredTool deleteTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("id", idProperty(type));

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("delete_" + type.noun)
        .title("Delete a CEDAR " + type.noun + " on the server")
        .description(
            "Permanently deletes a CEDAR " + type.noun + " from the server by its @id (IRI). "
                + "DESTRUCTIVE and irreversible — confirm with the user before calling. WRITES to the "
                + "server.")
        .inputSchema(schema(properties, List.of("id")))
        .build();

    BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (exchange, request) -> {
          Map<String, Object> args = args(request);
          String id = str(args, "id");
          if (id == null || id.isBlank())
            return error("id is required (the artifact's @id IRI)");
          CedarHttp.CedarResponse response;
          try {
            response = http.request("DELETE", idPath(type, id), null);
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          if (response.isSuccess())
            return success("Deleted " + type.noun + ": " + id);
          return error("CEDAR returned HTTP " + response.status() + ": " + response.body());
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- helpers

  /** An upload body prepared for the server, plus an optional note about a degraded preparation. */
  private record Prepared(ObjectNode body, String note) {}

  /**
   * Prepare an instance body for upload by inflating it against its template — the server's
   * validator requires every template property present, but a YAML-sourced instance is sparse.
   * The template is fetched from the server by the instance's {@code schema:isBasedOn}. Best-effort:
   * if there is no {@code schema:isBasedOn}, the template can't be fetched, or inflation fails, the
   * body is returned unchanged with a note explaining the skip — so an unreachable template never
   * blocks the upload outright (worst case is the server's own rejection, now with context).
   * A non-instance body is returned untouched.
   */
  private static Prepared prepareInstanceBody(ArtifactType type, ObjectNode body, CedarHttp http)
  {
    if (type != ArtifactType.INSTANCE)
      return new Prepared(body, null);

    String templateIri = ArtifactCodec.isBasedOn(body);
    if (templateIri == null || templateIri.isBlank())
      return new Prepared(body, "the instance has no schema:isBasedOn, so its template could not be "
          + "located to materialize empty fields; uploaded as-is");

    CedarHttp.CedarResponse templateResponse;
    try {
      templateResponse = http.request("GET", idPath(ArtifactType.TEMPLATE, templateIri), null);
    } catch (RuntimeException e) {
      return new Prepared(body, "could not fetch the template " + templateIri + " to materialize "
          + "empty fields (" + e.getMessage() + "); uploaded as-is");
    }
    if (!templateResponse.isSuccess())
      return new Prepared(body, "could not fetch the template " + templateIri + " (server returned "
          + templateResponse.status() + ") to materialize empty fields; uploaded as-is");

    try {
      ObjectNode templateJson = ArtifactCodec.asObjectNode(templateResponse.body());
      return new Prepared(ArtifactCodec.inflateInstance(templateJson, body), null);
    } catch (RuntimeException e) {
      return new Prepared(body, "could not inflate the instance against template " + templateIri
          + " (" + e.getMessage() + "); uploaded as-is");
    }
  }

  /**
   * Return the server's artifact, rendered as YAML (the compact exchange form) by default or as
   * pretty JSON when {@code asYaml} is false; a non-2xx is surfaced as an error result. A non-null
   * {@code note} (e.g. "the template couldn't be fetched to materialize empty fields") is appended
   * to an error so the caller learns why a degraded upload may have been rejected; a success result
   * is returned verbatim so the YAML can thread onward unaltered.
   */
  private static McpSchema.CallToolResult artifactResult(
      ArtifactType type, CedarHttp.CedarResponse response, boolean asYaml, String note)
  {
    if (!response.isSuccess()) {
      String base = "CEDAR returned HTTP " + response.status() + ": " + response.body();
      return error(note == null ? base : base + "\n\nNote: " + note);
    }
    ObjectNode node;
    try {
      node = ArtifactCodec.asObjectNode(response.body());
    } catch (RuntimeException e) {
      // 2xx but a body we can't parse as a JSON object — hand back the raw bytes so the caller
      // still sees what came over the wire.
      return success(response.body());
    }
    if (asYaml) {
      try {
        return success(ArtifactCodec.toYaml(type, node));
      } catch (RuntimeException e) {
        // The body parsed as JSON but the model reader / YAML renderer balked (an unusual or
        // partial artifact). Fall back to JSON so the fetch still returns something usable.
        return success(ArtifactCodec.prettyJson(node));
      }
    }
    return success(ArtifactCodec.prettyJson(node));
  }

  /** True only when the caller explicitly asked for JSON; absent or anything else means YAML. */
  private static boolean wantsJson(Map<String, Object> args)
  {
    return "json".equalsIgnoreCase(str(args, "format"));
  }

  /** For instances, reassures the caller that sparse YAML is fine — empty fields get materialized. */
  private static String instanceUploadHint(ArtifactType type)
  {
    return type == ArtifactType.INSTANCE
        ? " A sparse instance is fine — its empty fields are materialized against its template "
            + "(fetched via the instance's schema:isBasedOn) before upload, so pass the lean YAML "
            + "directly — no need to fill in empty fields or convert to JSON."
        : "";
  }

  /**
   * Tells the caller not to build CEDAR JSON-LD by hand, and names the three shapes that make it a
   * losing errand. Every tool that accepts an artifact carries this: an LLM holding a template's
   * JSON Schema will otherwise derive the matching JSON-LD from it, which is reasonable, laborious,
   * and wrong in ways the schema does not advertise.
   */
  private static String noHandBuiltJsonLd()
  {
    return " Do not hand-author CEDAR JSON-LD. Its @context block, the @id every nested element "
        + "instance carries, and the attribute-value shape are all easy to get wrong and are not "
        + "obvious from a template's JSON Schema. Author the compact YAML instead and let this tool "
        + "produce the JSON.";
  }

  /**
   * The compact YAML value vocabulary, in full, for instances. Duplicated into every tool that takes
   * an instance rather than kept in one place: the shape question ("what does a populated field look
   * like?") is asked at the moment of authoring, and a caller reading create_instance cannot be
   * expected to have read a rendering tool's description first.
   */
  private static String instanceValueVocabulary(ArtifactType type)
  {
    if (type != ArtifactType.INSTANCE)
      return "";
    return "\n\nChild values under 'children:', keyed by the template's child key:\n"
        + "  text, textarea, email, phone   value: Bob\n"
        + "  numeric                        datatype: xsd:int, value: 42\n"
        + "  temporal                       datatype: xsd:date, value: 2026-05-04\n"
        + "  radio, checkbox, list          value: Option A   (one of the field's declared literals)\n"
        + "  controlled term, link, ext-*   id: <IRI>, label: disease   (IRI-valued, not a literal)\n"
        + "  language-tagged literal        value: Bob, language: en\n"
        + "  multi-instance field           a list of the above, e.g. [{value: one}, {value: two}]\n"
        + "  element                        children: {...}; multi-instance, a list of those\n"
        + "Two rules the template states and a generated instance must honour: a multi-instance "
        + "field's own minItems/maxItems (they differ per field — one may demand three entries while "
        + "its neighbour allows one), and static fields (section break, page break, rich text, image, "
        + "video) which carry no value at all and are omitted entirely.\n"
        + "Attribute-value fields have no compact-form spelling today: the JSON form expects an array "
        + "of attribute *names* with each value as a sibling property. Omit them — they are optional — "
        + "rather than guessing a shape that will be rejected.";
  }

  private static Map<String, Object> idProperty(ArtifactType type)
  {
    return Map.of("type", "string", "description",
        "The " + type.noun + "'s @id — the full CEDAR IRI (e.g. "
            + "https://repo.metadatacenter.org/" + type.pathSegment + "/<uuid>). URL-encoding is "
            + "handled for you; pass the plain IRI.");
  }

  /**
   * Points a caller fetching a template at the cheap way to author an instance from it. Placed on
   * the fetch rather than only on create because the choice of representation is made here: a caller
   * that has already pulled the template as JSON tends to keep working in JSON, and by the time it
   * reads create_instance the expensive path is under way.
   */
  private static String authoringPointer(ArtifactType type)
  {
    return type == ArtifactType.TEMPLATE
        ? " To author an instance of this template, send create_instance compact YAML naming this "
            + "template in isBasedOn — you do not need to send the template back, and you do not "
            + "need to derive JSON-LD from its JSON Schema."
        : "";
  }

  private static Map<String, Object> artifactProperty(ArtifactType type)
  {
    return Map.of("type", "string", "description",
        "The CEDAR " + type.noun + " as YAML (the compact exchange form cedar-artifact-mcp "
            + "produces); JSON is also accepted. Pass it inline, verbatim."
            + (type == ArtifactType.INSTANCE
                ? " Author it as compact YAML rather than as CEDAR JSON-LD; see the tool description "
                    + "for the child value vocabulary."
                : ""));
  }

  private static Map<String, Object> formatProperty()
  {
    return Map.of(
        "type", "string",
        "enum", List.of("yaml", "json"),
        "description",
        "Output format for the returned artifact. Leave it unset (or \"yaml\") to get the compact "
            + "exchange form — an order of magnitude smaller than JSON and lossless. Pass \"json\" "
            + "only when a downstream tool can't read YAML. YAML is the default.");
  }

  private static McpSchema.JsonSchema schema(Map<String, Object> properties, List<String> required)
  {
    return new McpSchema.JsonSchema("object", properties, required, Boolean.FALSE, null, null);
  }

  private static String idPath(ArtifactType type, String id)
  {
    return "/" + type.pathSegment + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
  }

  private static Map<String, Object> args(McpSchema.CallToolRequest request)
  {
    return request.arguments() == null ? Map.of() : request.arguments();
  }

  private static String str(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static McpSchema.CallToolResult success(String text)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, text)))
        .isError(false)
        .build();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}
