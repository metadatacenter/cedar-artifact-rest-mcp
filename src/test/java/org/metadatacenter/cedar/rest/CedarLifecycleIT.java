package org.metadatacenter.cedar.rest;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration tests against a real CEDAR server, using canned templates from the
 * {@code cedar-artifact-library} test resources (the sibling checkout). For each template:
 * validate → create → get → delete, with the created {@code @id} deleted in a finally block so
 * nothing is left on the server.
 *
 * <p>Tagged {@code live} and <strong>excluded from the default build</strong> (failsafe
 * {@code excludedGroups=live}), mirroring {@code bioportal-term-mcp}'s {@code live} pytest marker.
 * Run on demand: {@code mvn verify -Plive} with {@code CEDAR_API_KEY} (and optionally
 * {@code CEDAR_BASE_URL}) set in the environment. Without a key, the tests self-skip.
 */
@Tag("live")
final class CedarLifecycleIT
{
  // Sibling-checkout path to the library's canned templates.
  private static final Path CANNED =
      Path.of("..", "..", "cedar-artifact-library", "src", "test", "resources", "templates-json");
  private static final Pattern TEMPLATE_IRI =
      Pattern.compile("https?://[^\\s\"']*/templates/[0-9A-Fa-f-]{8,}");

  private static CedarHttp http;

  @BeforeAll static void requireKey()
  {
    String key = System.getenv("CEDAR_API_KEY");
    assumeTrue(key != null && !key.isBlank(), "CEDAR_API_KEY not set; skipping live integration tests");
    http = new DefaultCedarHttp(CedarConfig.fromEnv());
  }

  @ParameterizedTest
  @ValueSource(strings = {"SimpleTemplate.json", "SimpleTemplateWithType.json", "ContributorV2.0.0.json"})
  void validate_create_get_delete_roundtrip(String fileName) throws Exception
  {
    Path file = CANNED.resolve(fileName);
    assumeTrue(Files.exists(file), "canned template not found at " + file.toAbsolutePath()
        + " (cedar-artifact-library checkout expected as a sibling)");
    String artifact = Files.readString(file);

    CallToolResult validate = call("validate_artifact", Map.of("artifact", artifact));
    assertFalse(validate.isError(), "validate failed: " + text(validate));

    String id = null;
    try {
      CallToolResult created = call("create_template", Map.of("artifact", artifact));
      assertFalse(created.isError(), "create failed: " + text(created));
      id = extractId(text(created));
      assertNotNull(id, "created template did not report an @id; got:\n" + text(created));

      CallToolResult fetched = call("get_template", Map.of("id", id));
      assertFalse(fetched.isError(), "get failed: " + text(fetched));
    } finally {
      if (id != null) {
        CallToolResult deleted = call("delete_template", Map.of("id", id));
        // Cleanup must succeed — surface a failure loudly rather than leaving the artifact behind.
        assertFalse(deleted.isError(), "CLEANUP failed for " + id + ": " + text(deleted));
      }
    }
  }

  @Test void update_changes_a_field_and_persists() throws Exception
  {
    // PUT coverage: create a template, change a field via update_template, confirm it persisted.
    // The server's PUT response is a resource wrapper (pathInfo/permissions), not the bare
    // artifact, so the change is verified with a follow-up get — which returns the artifact form —
    // rather than parsed off the PUT response.
    String original = "type: template\nname: PUT Lifecycle IT\ndescription: original\n";

    String id = null;
    try {
      CallToolResult created = call("create_template", Map.of("artifact", original));
      assertFalse(created.isError(), "create failed: " + text(created));
      id = extractId(text(created));
      assertNotNull(id, "created template did not report an @id; got:\n" + text(created));

      String updated = "type: template\nname: PUT Lifecycle IT\ndescription: changed\nid: " + id + "\n";
      CallToolResult put = call("update_template", Map.of("id", id, "artifact", updated));
      assertFalse(put.isError(), "update (PUT) failed: " + text(put));

      CallToolResult fetched = call("get_template", Map.of("id", id));
      assertFalse(fetched.isError(), "get-after-update failed: " + text(fetched));
      assertTrue(text(fetched).contains("changed"),
          "the updated description must persist; got:\n" + text(fetched));
    } finally {
      if (id != null) {
        CallToolResult deleted = call("delete_template", Map.of("id", id));
        assertFalse(deleted.isError(), "CLEANUP failed for " + id + ": " + text(deleted));
      }
    }
  }

  private static CallToolResult call(String toolName, Map<String, Object> args)
  {
    if (toolName.equals("validate_artifact"))
      return ValidateArtifactTool.create(http).handler().apply(null, new CallToolRequest(toolName, args));
    for (ArtifactCrudTools.RegisteredTool rt : ArtifactCrudTools.all(http))
      if (rt.tool().name().equals(toolName))
        return rt.handler().apply(null, new CallToolRequest(toolName, args));
    throw new IllegalArgumentException("no such tool: " + toolName);
  }

  private static String extractId(String text)
  {
    Matcher m = TEMPLATE_IRI.matcher(text);
    return m.find() ? m.group() : null;
  }

  private static String text(CallToolResult result)
  {
    List<McpSchema.Content> content = result.content();
    return content == null || content.isEmpty() ? "(no content)"
        : ((McpSchema.TextContent) content.get(0)).text();
  }
}
