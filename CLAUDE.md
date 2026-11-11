# For Claude (or any new contributor)

Start with these, in order:

1. **[README.md](./README.md)** — what this project is, how to build and run it, the tool
   inventory, configuration, and current status.
2. **[DESIGN.md](./DESIGN.md)** — the architectural principles. Read this *before* adding a tool,
   or you'll be tempted to put logic here that belongs in the calling LLM or in
   `cedar-artifact-mcp`. This MCP is a thin, honest conduit to the CEDAR REST API.
3. **[CEDAR MCP Servers Roadmap](https://github.com/metadatacenter/cedar-development/blob/develop/ops/MCP-ROADMAP.md)** — what is left to do, here and across the
   four servers. DESIGN.md states what is out of scope.

After those three, the code is self-explanatory. Patterns to mirror:

- **CRUD tools are generated, not hand-written.** The four artifact kinds differ only by path
  segment and tool noun (`ArtifactType`), so `ArtifactCrudTools.all(http)` builds all 16
  `get/create/update/delete` tools; the server registers them in a loop. Add a kind by extending
  the enum, not by writing four classes. One-off tools (e.g. `validate_artifact`) are their own
  class returning an `ArtifactCrudTools.RegisteredTool`.
- **All network I/O goes through the `CedarHttp` seam.** Handlers never touch `HttpClient`
  directly — they call `http.request(...)`. This is what makes them unit-testable against a fake
  transport (see `CedarRestToolsTest`). Don't bypass it.
- **An artifact travels in the serialization it was written in.** The server reads and writes both
  YAML and JSON, so a body goes as it came and a response is asked for in the form the caller wants.
  Nothing is converted, a sparse instance included — the server completes that. `ArtifactCodec`
  owns every inspection of an artifact, and nothing here reads one into the model: keep it that way,
  or the `cedar-artifact-library` dependency comes back.
- **`create` leaves identity to the server; `update` preserves the `@id`.** See DESIGN.md
  Principle 4.
- **Errors are content** (DESIGN.md Principle 5): a non-2xx response is an `isError` result
  carrying the HTTP status and server body — never a thrown protocol error.
- **Mutating tools are flagged in their descriptions.** `delete_*` is destructive; its description
  tells the LLM to confirm with the user. The tool surface is the LLM's only documentation —
  be concrete about inputs, outputs, the `@id` behaviour, and the danger of writes.
- **Secrets never appear in code, logs, or output.** The API key comes from `CEDAR_API_KEY` and is
  only ever placed in the `Authorization` header (`CedarConfig`).

## Testing

- **Default build runs mocked tests only.** Unit tests drive the handlers through a fake
  `CedarHttp` (`CedarRestToolsTest`) — no key, no server. Every new tool gets one.
- **The whole-stack smoke drives the built jar against a real server.** The REST tier's `mcp`
  suite (`cedar-development/ops/e2e/rest/suites/mcp.mjs`) starts `target/cedar-artifact-rest-mcp.jar`
  over stdio as the first test user and takes templates through validate, create, get, update and
  delete on the local stack. It fails when the jar is older than its sources, so rebuild with
  `mvn package` before a smoke run.

## Build conventions

- Java 17 source/target.
- `mvn package` produces an executable shaded jar at `target/cedar-artifact-rest-mcp-<version>-all.jar`.
- The shade plugin needs explicit filters to strip Jackson 2.x annotation classes bundled inside
  `cedar-model-library` / `cedar-model-validation-library` (they shadow the Jackson 3.x
  annotations the MCP SDK requires). See `pom.xml`; do not remove them.
- Comments describe code-level facts only — no references to PRs, sessions, or "this change".

## What's not in scope

This MCP manages **artifacts** through the resource server, nothing else. Folders, categories,
search/discovery, users, groups, and permissions are out of scope (DESIGN.md). Artifact
*construction*, conversion, and client-side validation live in `cedar-artifact-mcp`; the two
compose (build there, persist here). If you're tempted to add file I/O or model-building logic
here, stop — it belongs elsewhere.
