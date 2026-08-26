package org.metadatacenter.cedar.rest;

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
 * <p>Conventions: artifact IDs are IRIs, URL-encoded into the path. An artifact travels in the
 * serialization the caller used — YAML, the compact exchange form, unless the caller passes JSON —
 * and comes back in the one the caller asked for, YAML by default. The server reads and writes
 * both, so neither direction is transcoded here. {@code create} strips the identifier the artifact
 * arrived with, since the server mints it. Non-2xx responses surface the server's status and body
 * as an error result (errors are content).
 */
final class ArtifactCrudTools
{
  /**
   * The compact YAML value vocabulary shared by every tool that accepts a template instance.
   * Keep this as one block: create, update, and validate must teach the same wire shape.
   */
  static final String INSTANCE_VALUE_VOCABULARY =
      "\n\nChild values under 'children:', keyed by the template's child key:\n"
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
          + "An attribute-value field uses a top-level group alongside 'children:', named with the "
          + "template's field key. Each entry is keyed by the user-chosen attribute name and has the "
          + "literal field shape (value, with optional language); for example:\n"
          + "  Custom Properties:\n"
          + "    color:\n"
          + "      value: red\n"
          + "    size:\n"
          + "      value: large\n"
          + "Attribute values are literal-only: use value, not id.";

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
            response = http.request("GET", idPath(type, id), null, null, wanted(args));
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return response.isSuccess() ? success(response.body()) : serverError(response);
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
                + "folder). Do not supply an identifier: the server mints the artifact's @id and "
                + "every child identifier, and any id the artifact carries is dropped before it is "
                + "sent. The created artifact comes back as YAML (the compact exchange form), or as "
                + "JSON only if you pass format: json, carrying the identifiers the server assigned. "
                + "WRITES to the server. Supply the artifact inline as YAML (the compact form "
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
          Upload upload;
          try {
            upload = prepareUpload(text, true);
          } catch (RuntimeException e) {
            return error("artifact could not be read as YAML or JSON: " + e.getMessage());
          }
          CedarHttp.CedarResponse response;
          try {
            response = http.request("POST", "/" + type.pathSegment,
                upload.body(), upload.format(), wanted(args));
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return response.isSuccess() ? success(response.body()) : serverError(response);
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
                + "@id in the artifact body must match the id argument. Returns the stored artifact "
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
          Upload upload;
          try {
            upload = prepareUpload(text, false);
          } catch (RuntimeException e) {
            return error("artifact could not be read as YAML or JSON: " + e.getMessage());
          }
          CedarHttp.CedarResponse response;
          try {
            // JSON on the way back: a successful PUT answers with the folder record of the artifact
            // — its path, permissions and folder — not the artifact, and that record has no YAML
            // form. The stored artifact is re-fetched below in the serialization asked for.
            response = http.request("PUT", idPath(type, id),
                upload.body(), upload.format(), ArtifactFormat.JSON);
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          if (!response.isSuccess())
            return serverError(response);
          return refetch(type, id, http, wanted(args), response);
        };

    return new RegisteredTool(tool, handler);
  }

  /**
   * Answer an update with the stored artifact, fetched after the write. The PUT itself answers with
   * the folder record rather than the artifact, so returning that would make update the one tool in
   * the surface whose result is not the artifact it just wrote. If the fetch fails the write still
   * happened, so the folder record is returned instead, with what went wrong.
   */
  private static McpSchema.CallToolResult refetch(ArtifactType type, String id, CedarHttp http,
      ArtifactFormat format, CedarHttp.CedarResponse writeResponse)
  {
    CedarHttp.CedarResponse fetched;
    try {
      fetched = http.request("GET", idPath(type, id), null, null, format);
    } catch (RuntimeException e) {
      return success(writeResponse.body() + "\n\nNote: the " + type.noun + " was updated, but "
          + "re-reading it failed (" + e.getMessage() + "), so the server's write response is "
          + "returned in its place.");
    }
    if (fetched.isSuccess())
      return success(fetched.body());
    return success(writeResponse.body() + "\n\nNote: the " + type.noun + " was updated, but "
        + "re-reading it returned HTTP " + fetched.status() + ", so the server's write response is "
        + "returned in its place.");
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
            response = http.request("DELETE", idPath(type, id), null, null, ArtifactFormat.JSON);
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          if (response.isSuccess())
            return success("Deleted " + type.noun + ": " + id);
          return serverError(response);
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- helpers

  /** A body ready for the server and the serialization it is written in. */
  record Upload(String body, ArtifactFormat format) {}

  /**
   * Prepare an artifact for a write: it is sent exactly as the caller wrote it, less the identity on
   * a create. Nothing else is done to it, an instance included — a sparse one is completed by the
   * server, against the template its {@code isBasedOn} names.
   */
  static Upload prepareUpload(String text, boolean forCreate)
  {
    return new Upload(forCreate ? ArtifactCodec.askServerToMintIdentifier(text) : text,
        ArtifactCodec.formatOf(text));
  }

  /** A non-2xx as an error result carrying the server's status and body. */
  private static McpSchema.CallToolResult serverError(CedarHttp.CedarResponse response)
  {
    return error("CEDAR returned HTTP " + response.status() + ": " + response.body());
  }

  /** The serialization the caller asked the artifact back in; YAML unless they said json. */
  private static ArtifactFormat wanted(Map<String, Object> args)
  {
    return ArtifactFormat.fromArgument(str(args, "format"));
  }

  /** For instances, reassures the caller that sparse YAML is fine — empty fields get materialized. */
  private static String instanceUploadHint(ArtifactType type)
  {
    return type == ArtifactType.INSTANCE
        ? " A sparse instance is fine — the server completes it against the template its isBasedOn "
            + "names, so pass the lean YAML directly. Do not fill in empty fields: YAML has no way "
            + "to write one."
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
        + "obvious from a template's JSON Schema. Author the compact YAML instead and let the "
        + "server produce the JSON.";
  }

  /** Append the shared instance vocabulary only to tools that take an instance artifact. */
  private static String instanceValueVocabulary(ArtifactType type)
  {
    return type == ArtifactType.INSTANCE ? INSTANCE_VALUE_VOCABULARY : "";
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
