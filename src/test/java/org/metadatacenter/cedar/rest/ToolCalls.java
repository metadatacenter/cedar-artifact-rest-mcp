package org.metadatacenter.cedar.rest;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/** Builds the request a client sends to call a tool, for handlers driven directly in tests. */
final class ToolCalls
{
  private ToolCalls() {}

  static McpSchema.CallToolRequest request(String name, Map<String, Object> arguments)
  {
    return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
  }
}
