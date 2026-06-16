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
 * <p>Conventions: artifact IDs are IRIs, URL-encoded into the path. Artifact bodies may be supplied
 * as YAML (the compact exchange form) or JSON — YAML is converted to canonical CEDAR JSON via
 * {@code cedar-artifact-library} before it's sent. Responses come back as JSON; render a fetched
 * artifact to YAML for display with {@code cedar-artifact-mcp}'s {@code *_to_yaml}. {@code create}
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

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("get_" + type.noun)
        .title("Fetch a CEDAR " + type.noun + " from the server")
        .description(
            "Fetches a CEDAR " + type.noun + " from the CEDAR server by its @id (IRI). Returns the "
                + "artifact as CEDAR JSON. To view it as compact YAML, pass the result to "
                + "cedar-artifact-mcp's " + type.noun + "_to_yaml. Reproduce the returned JSON "
                + "verbatim — do not drop @id lines or summarize.")
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
          return artifactResult(response);
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- create

  private static RegisteredTool createTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", artifactProperty(type));

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("create_" + type.noun)
        .title("Create a CEDAR " + type.noun + " on the server")
        .description(
            "Creates a new CEDAR " + type.noun + " on the CEDAR server (it is placed in your home "
                + "folder). The artifact's top-level @id is set to null on submission; the server "
                + "assigns the real @id and returns the created artifact as JSON. WRITES to the "
                + "server. Supply the artifact inline as YAML (the compact form cedar-artifact-mcp "
                + "returns) or as JSON — either is accepted; pass it verbatim, don't reformat.")
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
            ArtifactCodec.nullifyTopLevelId(body);
          } catch (RuntimeException e) {
            return error("artifact could not be parsed as JSON or YAML: " + e.getMessage());
          }
          CedarHttp.CedarResponse response;
          try {
            response = http.request("POST", "/" + type.pathSegment, ArtifactCodec.compactJson(body));
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return artifactResult(response);
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- update

  private static RegisteredTool updateTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("id", idProperty(type));
    properties.put("artifact", artifactProperty(type));

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("update_" + type.noun)
        .title("Update a CEDAR " + type.noun + " on the server")
        .description(
            "Updates an existing CEDAR " + type.noun + " on the server (PUT) by its @id (IRI). The "
                + "@id in the artifact body must match the id argument. Returns the updated artifact "
                + "as JSON. WRITES to the server. Supply the artifact inline as YAML (the compact "
                + "form cedar-artifact-mcp returns) or as JSON — either is accepted; pass it "
                + "verbatim, don't reformat.")
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
          CedarHttp.CedarResponse response;
          try {
            response = http.request("PUT", idPath(type, id), ArtifactCodec.compactJson(body));
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return artifactResult(response);
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

  /** Return the server's artifact JSON pretty-printed, or surface a non-2xx as an error result. */
  private static McpSchema.CallToolResult artifactResult(CedarHttp.CedarResponse response)
  {
    if (!response.isSuccess())
      return error("CEDAR returned HTTP " + response.status() + ": " + response.body());
    try {
      return success(ArtifactCodec.prettyJson(ArtifactCodec.asObjectNode(response.body())));
    } catch (RuntimeException e) {
      // 2xx but a body we can't parse as a JSON object — hand back the raw bytes so the caller
      // still sees what came over the wire.
      return success(response.body());
    }
  }

  private static Map<String, Object> idProperty(ArtifactType type)
  {
    return Map.of("type", "string", "description",
        "The " + type.noun + "'s @id — the full CEDAR IRI (e.g. "
            + "https://repo.metadatacenter.org/" + type.pathSegment + "/<uuid>). URL-encoding is "
            + "handled for you; pass the plain IRI.");
  }

  private static Map<String, Object> artifactProperty(ArtifactType type)
  {
    return Map.of("type", "string", "description",
        "The CEDAR " + type.noun + " as YAML (the compact exchange form cedar-artifact-mcp "
            + "produces) or as JSON. Pass it inline, verbatim.");
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
