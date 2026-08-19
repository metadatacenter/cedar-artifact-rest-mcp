# Roadmap — cedar-artifact-rest-mcp

`cedar-artifact-rest-mcp` wraps the CEDAR **resource server** REST API to manage artifacts —
templates, template-elements, template-fields, and template-instances. It is the I/O
counterpart to `cedar-artifact-mcp`. That one builds, converts, and validates artifacts
in memory; this one persists them to, and fetches them from, a live CEDAR server.

This document records what's in the first version, what's deliberately deferred, and what
is out of scope, so the boundaries don't drift.

## Scope — v1

- **CRUD** for the four artifact types: `get` / `create` / `update` / `delete` ×
  {template, element, field, instance} (16 tools).
- **Server-side validation**: `validate_artifact` → `POST /command/validate` (the
  authoritative meta-model validator; complements `cedar-artifact-mcp`'s client-side one).
- **YAML or JSON on the wire**: an artifact is sent to the server in the serialization the caller
  wrote it in and comes back in the one they asked for — YAML by default, an order of magnitude
  smaller than CEDAR's JSON-LD and lossless, or JSON with `format: json`. The server reads and
  writes both, so neither direction is transcoded here — a sparse instance included, which the
  server completes against its template. Nothing here reads an artifact into the model, so this MCP
  carries no `cedar-artifact-library` dependency and resolves from Maven Central alone.
- **Identity left to the server**: CEDAR mints every identifier, so `create_*` strips the one an
  artifact arrived with — a JSON `@id` becomes `null`, a YAML `id:` key is dropped — and the
  identifiers the server assigned come back in the response. `update_*` preserves the `@id` (it
  identifies the artifact; path `{id}` and body `@id` must agree).
- **Update answers with the artifact**: a successful `PUT` answers with the artifact's folder
  record — its path, permissions and folder — rather than the artifact, so `update_*` re-reads the
  artifact afterwards and returns that, in the serialization asked for. The folder record has no
  YAML rendering, which is also why the write itself always asks for JSON back.
- **Auth / config**: `CEDAR_API_KEY` (required) and `CEDAR_BASE_URL` (default the
  production resource server) via environment; `Authorization: apiKey <KEY>` header.

## Deferred (planned, not in v1)

- **`folder_id` on create** — v1 creates artifacts in the caller's home folder. Add the
  optional `folder_id` query parameter (`POST /templates?folder_id=<IRI>`, etc.) so an
  artifact can be placed in a chosen folder.
- **Reports & versions** — `GET /{type}/{id}/details`, `/report`, `/versions` (read-only
  metadata and version history).
- **Lifecycle / versioning** — `/command/create-draft-artifact`, `/command/publish-artifact`,
  `make-artifact-open` / `make-artifact-not-open`: the draft → publish workflow. These are
  mutating and partly irreversible; revisit deliberately.
## Out of scope

The non-artifact REST surface, left to other tooling:

- **Discovery**: `/search`, `/search-deep`, `/folders/{id}/contents`. v1 therefore operates
  by artifact **IRI** — you fetch what you can name, or what a `create` just returned;
  finding artifacts by query is not available.
- **Folders, categories, users, groups.**
- **Permissions** (`GET` / `PUT /{type}/{id}/permissions`).
- **Resource organization commands**: `copy-artifact-to-folder`, `move-resource-to-folder`,
  `rename-resource`, `attach-category` / `detach-category`.
- **Index maintenance commands** (`regenerate-search-index`, `regenerate-rules-index`, …).

## Findings

- **The server completes a sparse instance.** An instance write was the one call still leaving here
  as JSON: the empty fields a stored instance's JSON must carry have no YAML spelling, so they were
  materialized here first. They are materialized on the server now, in `ArtifactYamlTranscoder`,
  which is where the YAML becomes the JSON that needs them. Completing every instance write was
  tried first and was wrong: it repaired documents a JSON client had deliberately broken, which the
  artifact server's own suite caught. Omission means "empty" in YAML and "deleted" in JSON, and only
  the serialization tells them apart.
- **A `PUT` asking for YAML answered 500.** The resource server negotiates an artifact response by
  re-rendering the entity when the client asked for YAML, and returns it untouched when it cannot —
  an error, or a `PUT`'s folder record. Untouched left the media type unset, so JAX-RS picked the
  negotiated YAML and then found no writer for a `FolderServerTemplate`. Fixed in
  `AbstractResourceServerResource.negotiateArtifactResponse` (and the artifact server's copy) by
  naming JSON on the pass-through. `update_*` asks for JSON on the write regardless, so it is
  unaffected either way.
- **A nested element instance validates again.** `cedar-artifact-mcp` recorded that a template types
  a nested element occurrence's `@id` as a URI while the library renders `null` for one that has not
  been saved, so a freshly built instance could not validate against its own template. It now
  validates; the test that pinned the mismatch is an ordinary validation assertion again.

## Open questions (resolve during build)

- **Nested child `@id` on create** — *resolved.* Identity is stripped from the artifact itself and
  left on its children, which the server replaces anyway: a non-verbatim write mints a child
  identifier and a property IRI for every child. See DESIGN.md Principle 4.
- **Target server** — production (`resource.metadatacenter.org`) vs a local CEDAR stack (the
  `cedar-resource-server` checkout under `~/CEDAR`). Determines `CEDAR_BASE_URL` and which
  API key is valid.
