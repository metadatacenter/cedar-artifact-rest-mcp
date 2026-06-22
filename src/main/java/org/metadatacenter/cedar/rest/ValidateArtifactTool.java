package org.metadatacenter.cedar.rest;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Tool {@code validate_artifact} — validates a CEDAR artifact against the meta-model using the
 * server's authoritative {@code POST /command/validate}. Accepts the artifact as YAML (JSON is also
 * accepted); this codec converts YAML to JSON before sending (the server also accepts YAML). The kind
 * (template / element / field / instance) is
 * auto-detected from the {@code @type}. Returns the server's {@code {validates, warnings, errors}}
 * report.
 */
final class ValidateArtifactTool
{
  private ValidateArtifactTool() {}

  static ArtifactCrudTools.RegisteredTool create(CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of("type", "string", "description",
        "A CEDAR template, element, field, or instance as YAML (the compact exchange form "
            + "cedar-artifact-mcp produces); JSON is also accepted. Pass it inline, verbatim — "
            + "don't reformat it."));

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("validate_artifact")
        .title("Validate a CEDAR artifact on the server")
        .description(
            "Validates a CEDAR artifact against the CEDAR meta-model using the server's "
                + "/command/validate (authoritative). Supply the artifact as YAML (JSON is also "
                + "accepted); the "
                + "kind is auto-detected from its @type. Returns the server's report: "
                + "{\"validates\": true|false, \"warnings\": [...], \"errors\": [...]}. This is a "
                + "read-only call (no artifact is created).")
        .inputSchema(new McpSchema.JsonSchema("object", properties, List.of("artifact"),
            Boolean.FALSE, null, null))
        .build();

    BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (exchange, request) -> {
          Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
          Object raw = args.get("artifact");
          String text = raw == null ? null : raw.toString();
          if (text == null || text.isBlank())
            return error("artifact is required and must not be blank");

          ArtifactCodec.Detected detected;
          try {
            detected = ArtifactCodec.forValidation(text);
          } catch (RuntimeException e) {
            return error("artifact could not be parsed/identified: " + e.getMessage());
          }

          CedarHttp.CedarResponse response;
          try {
            response = http.request("POST",
                "/command/validate?resource_type=" + detected.type().validateResourceType,
                detected.json());
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }

          if (response.isSuccess())
            return success(response.body());
          return error("CEDAR returned HTTP " + response.status() + ": " + response.body());
        };

    return new ArtifactCrudTools.RegisteredTool(tool, handler);
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
