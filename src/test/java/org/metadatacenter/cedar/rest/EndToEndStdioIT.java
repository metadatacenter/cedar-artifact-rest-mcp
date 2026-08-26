package org.metadatacenter.cedar.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration test that launches the shaded jar and speaks JSON-RPC over its real
 * stdin/stdout. This catches startup, shading, stdio framing, and tool-registration failures that
 * in-process tests cannot expose. It deliberately calls only {@code ping}, so no live CEDAR server
 * or API key is needed.
 *
 * <p>Failsafe runs this untagged {@code *IT} during the default {@code mvn verify} lifecycle,
 * after the shaded jar has been built.
 */
final class EndToEndStdioIT
{
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  private final ObjectMapper jackson = new ObjectMapper();

  @Test void shaded_jar_initializes_lists_tools_and_pings() throws Exception
  {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Process server = new ProcessBuilder(java.toString(), "-jar", locateShadedJar().toString())
        .redirectErrorStream(false)
        .start();
    StringBuilder stderr = drainStderr(server);
    BufferedReader stdout = new BufferedReader(
        new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

    try (Writer stdin = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8)) {
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
          + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
          + "\"clientInfo\":{\"name\":\"e2e\",\"version\":\"0\"}}}");
      JsonNode initialized = readResponse(stdout, stderr);
      assertEquals(1, initialized.path("id").asInt());
      assertEquals("cedar-artifact-rest-mcp",
          initialized.path("result").path("serverInfo").path("name").asText());

      send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
      JsonNode listed = readResponse(stdout, stderr);
      assertEquals(2, listed.path("id").asInt());
      List<String> names = new ArrayList<>();
      listed.path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
      for (String noun : List.of("template", "element", "field", "instance"))
        for (String verb : List.of("get", "create", "update", "delete"))
          assertTrue(names.contains(verb + "_" + noun),
              "missing " + verb + "_" + noun + "; got " + names);
      assertTrue(names.contains("validate_artifact"), "missing validate_artifact; got " + names);
      assertTrue(names.contains("ping"), "missing ping; got " + names);
      assertEquals(18, names.size(), "unexpected registered tools: " + names);

      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
          + "{\"name\":\"ping\",\"arguments\":{\"message\":\"it\"}}}");
      JsonNode pinged = readResponse(stdout, stderr);
      assertEquals(3, pinged.path("id").asInt());
      assertFalse(pinged.path("result").path("isError").asBoolean(true), pinged.toString());
      assertTrue(pinged.path("result").path("content").get(0).path("text").asText()
              .startsWith("pong: it (cedar-artifact-rest-mcp "),
          "ping should identify the running server build; got " + pinged);
    } finally {
      stop(server);
    }
  }

  private static Path locateShadedJar() throws IOException
  {
    Path target = Path.of("target").toAbsolutePath();
    try (DirectoryStream<Path> jars = Files.newDirectoryStream(
        target, "cedar-artifact-rest-mcp-*-all.jar")) {
      for (Path jar : jars)
        return jar;
    }
    fail("no shaded jar found in " + target + "; failsafe should run after package");
    throw new AssertionError("unreachable");
  }

  private static void send(Writer stdin, String message) throws IOException
  {
    stdin.write(message);
    stdin.write('\n');
    stdin.flush();
  }

  private JsonNode readResponse(BufferedReader stdout, StringBuilder stderr) throws Exception
  {
    CompletableFuture<String> lineFuture = CompletableFuture.supplyAsync(() -> {
      try {
        return stdout.readLine();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });

    String line;
    try {
      line = lineFuture.get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      fail("timed out waiting for server response. Captured stderr:\n" + snapshot(stderr));
      throw new AssertionError("unreachable");
    } catch (ExecutionException e) {
      fail("stdout read failed: " + e.getCause() + ". Captured stderr:\n" + snapshot(stderr));
      throw new AssertionError("unreachable");
    }

    if (line == null) {
      fail("server closed stdout before responding. Captured stderr:\n" + snapshot(stderr));
      throw new AssertionError("unreachable");
    }
    return jackson.readTree(line);
  }

  private static StringBuilder drainStderr(Process server)
  {
    StringBuilder stderr = new StringBuilder();
    Thread pump = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(server.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null)
          synchronized (stderr) { stderr.append(line).append('\n'); }
      } catch (IOException ignored) {}
    }, "stderr-pump");
    pump.setDaemon(true);
    pump.start();
    return stderr;
  }

  private static String snapshot(StringBuilder stderr)
  {
    synchronized (stderr) {
      return stderr.toString();
    }
  }

  private static void stop(Process server) throws InterruptedException
  {
    server.destroy();
    if (!server.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      server.destroyForcibly();
      server.waitFor(5, TimeUnit.SECONDS);
    }
  }
}
