package org.metadatacenter.cedar.rest;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Tool {@code validate_artifact} — validates a CEDAR artifact against the meta-model using the
 * server's authoritative {@code POST /command/validate}. The artifact is sent in the serialization
 * the caller wrote it in, YAML or JSON, since the command reads both. The kind (template / element
 * / field / instance) is read from the YAML {@code type:} discriminator or the JSON {@code @type},
 * and names the {@code resource_type} the command is given. Returns the server's
 * {@code {validates, warnings, errors}} report.
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
                + "kind is auto-detected from its type. Returns the server's report: "
                + "{\"validates\": true|false, \"warnings\": [...], \"errors\": [...]}. This is a "
                + "read-only call (no artifact is created). Errors carry a JSON pointer at the "
                + "offending location, so validate a whole artifact and read the locations rather "
                + "than bisecting it."
                + " Do not hand-author CEDAR JSON-LD to validate it. Its @context block, the @id "
                + "every nested element instance carries, and the attribute-value shape are easy to "
                + "get wrong and are not obvious from a template's JSON Schema; author compact YAML."
                + "\n\nInstance child values under 'children:', keyed by the template's child key:\n"
                + "  text, textarea, email, phone   value: Bob\n"
                + "  numeric                        datatype: xsd:int, value: 42\n"
                + "  temporal                       datatype: xsd:date, value: 2026-05-04\n"
                + "  radio, checkbox, list          value: Option A   (one of the declared literals)\n"
                + "  controlled term, link, ext-*   id: <IRI>, label: disease   (IRI-valued)\n"
                + "  multi-instance field           a list of the above, honouring the field's own "
                + "minItems/maxItems\n"
                + "  element                        children: {...}; multi-instance, a list of those\n"
                + "Static fields (section break, page break, rich text, image, video) carry no value "
                + "and are omitted. Attribute-value fields have no compact-form spelling today — omit "
                + "them rather than guessing.")
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

          ArtifactType type;
          ArtifactCrudTools.Upload upload;
          try {
            type = ArtifactCodec.detectKind(text);
            upload = ArtifactCrudTools.prepareUpload(text, false);
          } catch (RuntimeException e) {
            return error("artifact could not be read or identified: " + e.getMessage());
          }

          CedarHttp.CedarResponse response;
          try {
            response = http.request("POST",
                "/command/validate?resource_type=" + type.validateResourceType,
                upload.body(), upload.format(), ArtifactFormat.JSON);
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
