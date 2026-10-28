package org.metadatacenter.cedar.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema a tool declares for its arguments. The MCP SDK takes it as a map, checks it
 * against the JSON Schema 2020-12 meta-schema when the server is built, and validates every call's
 * arguments against it before the handler runs.
 */
public final class ToolSchemas
{
  private ToolSchemas() {}

  /** An object with the given properties, of which those named in {@code required} must be present and no others are allowed. */
  public static Map<String, Object> object(Map<String, Object> properties, List<String> required)
  {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", required);
    schema.put("additionalProperties", Boolean.FALSE);
    return schema;
  }
}
