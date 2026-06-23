# Roadmap — cedar-artifact-rest-mcp

`cedar-artifact-rest-mcp` wraps the CEDAR **resource server** REST API to manage artifacts —
templates, template-elements, template-fields, and template-instances. It is the I/O
counterpart to `cedar-artifact-mcp`: that one builds, converts, and validates artifacts
in memory; this one persists them to, and fetches them from, a live CEDAR server.

This document records what's in the first version, what's deliberately deferred, and what
is out of scope, so the boundaries don't drift.

## Scope — v1

- **CRUD** for the four artifact types: `get` / `create` / `update` / `delete` ×
  {template, element, field, instance} (16 tools).
- **Server-side validation**: `validate_artifact` → `POST /command/validate` (the
  authoritative meta-model validator; complements `cedar-artifact-mcp`'s client-side one).
- **YAML or JSON on the boundary**: artifacts may be supplied as the compact YAML exchange form
  or as JSON; YAML is read into the model and converted to JSON via `cedar-artifact-library`
  before it is sent (the MCP speaks JSON to the server today, though the server now accepts YAML
  too — see the YAML-straight-through item under Deferred). Fetched artifacts are returned as YAML
  by default — an order of magnitude smaller and lossless — or as JSON when the caller passes
  `format: json`. A sparse instance is inflated against its template (fetched by its
  `schema:isBasedOn`) before `create` / `update`, so callers can hand over lean YAML. Because of
  the `cedar-artifact-library` dependency (a local SNAPSHOT today), this MCP does not resolve
  from Maven Central alone.
- **Create `@id` handling**: `create_*` forces the top-level `@id` to JSON `null` so the
  server assigns one; the assigned `@id` comes back in the response. `update_*` preserves
  the `@id` (it identifies the artifact; path `{id}` and body `@id` must agree).
- **Auth / config**: `CEDAR_API_KEY` (required) and `CEDAR_BASE_URL` (default the
  production resource server) via environment; `Authorization: apiKey <KEY>` header.

## Deferred (planned, not in v1)

- **Normalize the `update_*` (PUT) response** — the resource server's `PUT /{type}/{id}` returns a
  resource *wrapper* (`resourceType`, `pathInfo`, folder/permission metadata, `@context`) rather
  than the bare artifact that `get_*` / `create_*` return. Because that wrapper isn't a parseable
  CEDAR artifact, `update_*` falls back to returning the raw JSON wrapper instead of the clean YAML
  the rest of the surface emits. After a successful PUT, re-fetch the artifact (a follow-up `GET`)
  — or unwrap it from the response — and return it in the normal artifact form (YAML by default),
  so update matches `get` / `create`. Confirmed live against staging on 2026-06-23.
- **`folder_id` on create** — v1 creates artifacts in the caller's home folder. Add the
  optional `folder_id` query parameter (`POST /templates?folder_id=<IRI>`, etc.) so an
  artifact can be placed in a chosen folder.
- **Reports & versions** — `GET /{type}/{id}/details`, `/report`, `/versions` (read-only
  metadata and version history).
- **Lifecycle / versioning** — `/command/create-draft-artifact`, `/command/publish-artifact`,
  `make-artifact-open` / `make-artifact-not-open`: the draft → publish workflow. These are
  mutating and partly irreversible; revisit deliberately.
- **YAML straight through (skip the conversion)** — the CEDAR server now accepts and returns
  YAML, so this MCP could send the caller's YAML body unchanged and request YAML responses,
  dropping the YAML↔JSON conversion (and the inflate-before-send) hop entirely. Today it converts
  via `cedar-artifact-library`; going YAML-native would also shed that dependency, letting the MCP
  resolve from Maven Central again. Verify the server's YAML content-negotiation first.

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

## Open questions (resolve during build)

- **Nested child `@id` on create** — *resolved.* Only the top-level `@id` is nulled on POST;
  nested element/field `@id`s are submitted exactly as the artifact carries them, which is the
  correct behaviour (the server assigns the artifact's own identity and accepts the rest). See
  DESIGN.md Principle 4.
- **Target server** — production (`resource.metadatacenter.org`) vs a local CEDAR stack (the
  `cedar-resource-server` checkout under `~/CEDAR`). Determines `CEDAR_BASE_URL` and which
  API key is valid.
