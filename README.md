# cedar-rest-mcp

An MCP server that manages CEDAR **artifacts** — templates, elements, fields, and instances —
through the CEDAR **resource-server REST API**. It is the I/O counterpart to
[`cedar-artifact-mcp`](../cedar-artifact-mcp): that one builds, converts, and validates artifacts
in memory; this one persists them to, and fetches them from, a live CEDAR server.

See [DESIGN.md](./DESIGN.md) for the principles and [ROADMAP.md](./ROADMAP.md) for scope and
deferred work.

## Example workflow

A typical session looks like the following — natural-language prompts the user gives the LLM,
which it translates into REST MCP tool calls against a live CEDAR server. This MCP is the
**persistence half** of the pipeline: author and shape an artifact in memory with
[`cedar-artifact-mcp`](../cedar-artifact-mcp), then hand it here to validate, save, fetch, update,
or remove it. This MCP speaks the CEDAR server's wire format — **JSON** — both ways: artifacts go
in as JSON and come back as JSON. Converting to and from YAML (the compact, human-friendly
serialization) is `cedar-artifact-mcp`'s job — the LLM runs its `*_to_json` to produce the body it
saves here, and its `*_to_yaml` to render a fetched artifact for display. The YAML shown below is that
rendered view; the bytes crossing this MCP are JSON.

Assume the LLM already has a Patient Study template in hand, authored with `cedar-artifact-mcp`
(shown as the compact YAML the user sees):

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/76cf7229-d0ae-462a-a40a-e2f8eeb5d041
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
    id: https://repo.metadatacenter.org/template-fields/0252465c-1c51-4fae-a41a-a9263bc9dc31
  - key: Age
    type: numeric-field
    name: Age
    id: https://repo.metadatacenter.org/template-fields/cbb34a8a-d754-4425-b0e9-52f3db4ade08
    datatype: xsd:int
```

*Check it against CEDAR's validator before I save it.*

```json
{ "validates": true, "warnings": [], "errors": [] }
```

The LLM converts the template to JSON with `cedar-artifact-mcp`'s `template_to_json`, then
`validate_artifact` posts that to the server's authoritative validator and returns its report.
Nothing is created — it is a dry run, usable on any artifact including ones pulled from elsewhere.

That conversion is needed because the CEDAR server's REST API currently speaks **JSON only** —
every call here (validate, create, update) takes a JSON body, and the LLM renders responses back to
YAML for display with `template_to_yaml`. The server is expected to accept YAML directly in a later
release, at which point the conversion hop disappears; JSON is the server's current wire format,
not a privileged form of the artifact.

*Save it to CEDAR.*

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/0e8f3a91-7d2c-4b6a-9e1f-5a8c2d0b4e63
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
    id: https://repo.metadatacenter.org/template-fields/0252465c-1c51-4fae-a41a-a9263bc9dc31
  - key: Age
    type: numeric-field
    name: Age
    id: https://repo.metadatacenter.org/template-fields/cbb34a8a-d754-4425-b0e9-52f3db4ade08
    datatype: xsd:int
```

`create_template` deliberately blanks the top-level `@id` before sending. In CEDAR an artifact's
identity is the **repository's** to assign, not the author's: a template you build locally carries
only a provisional `@id` (above, `…/templates/76cf7229…`, minted by `cedar-artifact-mcp` so the
artifact is well-formed in transit), and on `create` the server discards it, mints an authoritative
IRI under its own namespace (`…/templates/0e8f3a91…`), and returns the stored artifact carrying
that id. From then on the server `@id` is the handle — it is what you pass to `get` / `update` /
`delete`. Only the **top-level** `@id` is reassigned; the embedded field ids (`0252465c…`,
`cbb34a8a…`) ride through unchanged.

Two server behaviors to expect on store: it **requires** a `version` and `status` (both supplied
automatically by `cedar-artifact-mcp` when it builds the artifact), and it **rewrites** the
JSON-Schema `title` / `description` while leaving `schema:name` / `schema:description` alone.

*Fetch it back.*

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/0e8f3a91-7d2c-4b6a-9e1f-5a8c2d0b4e63
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
    id: https://repo.metadatacenter.org/template-fields/0252465c-1c51-4fae-a41a-a9263bc9dc31
  - key: Age
    type: numeric-field
    name: Age
    id: https://repo.metadatacenter.org/template-fields/cbb34a8a-d754-4425-b0e9-52f3db4ade08
    datatype: xsd:int
```

`get_template` takes the artifact's `@id` (the full IRI); URL-encoding into the path is handled for
you. It returns the artifact as JSON; the LLM renders it back to the YAML above with
`template_to_yaml` for display.

*Bump it to version 1.0.0 and update it.*

`update_template` keeps the `@id` — the `id` argument and the body's `@id` must agree — and returns
the stored artifact. Unlike `create`, nothing is re-minted.

*Now create an instance with Patient Name = Alice and Age = 30.*

The CEDAR server requires an instance to carry **every** template field; a sparse instance is
rejected. So the LLM first asks `cedar-artifact-mcp` to produce a complete JSON instance
(`instance_to_json`, given the template), then persists it here with `create_instance` — which,
like `create_template`, lets the server assign the instance's `@id`.

*Delete the template.*

`delete_template` is **destructive and irreversible**, so the LLM confirms with the user before
calling it, then removes the artifact by IRI.

## Tools

Each tool is a thin wrapper over one CEDAR **resource-server** REST endpoint — `get` / `create` /
`update` / `delete` for each of the four artifact kinds, plus server-side validation. The four
kinds differ only by endpoint path, so the tools are generated per kind and behave identically
within an operation; they're documented once per operation below. This MCP speaks **JSON** both
ways (see [DESIGN.md](./DESIGN.md) Principle 3): artifact bodies go in as JSON and
responses come back as JSON — converting to/from YAML is `cedar-artifact-mcp`'s job (`*_to_json` /
`*_to_yaml`). A non-2xx server response is surfaced as an error result carrying the status and
body (errors are content, never thrown).

| Group | Tools |
|---|---|
| Fetch | `get_template` · `get_element` · `get_field` · `get_instance` |
| Create | `create_template` · `create_element` · `create_field` · `create_instance` |
| Update | `update_template` · `update_element` · `update_field` · `update_instance` |
| Delete | `delete_template` · `delete_element` · `delete_field` · `delete_instance` |
| Validate | `validate_artifact` |
| Diagnostics | `ping` |

**Conventions.** Artifacts are addressed by `@id` — the full CEDAR IRI; URL-encoding into the
request path is handled for you, so pass the plain IRI. The `artifact` body is CEDAR
**JSON** — the JSON Schema form, e.g. what `cedar-artifact-mcp`'s `*_to_json` produces; pass YAML and the tool redirects
you to convert it there first. Discovery (search, folder listing) is **out of scope** — you operate
by IRI: fetch what you can name, or what a `create` just returned.

### `get_{template,element,field,instance}(id)`

Fetches an artifact from the CEDAR server by its `@id` IRI (`GET /{type}/{id}`). Returns the
artifact as CEDAR JSON; render it to compact YAML for display with `cedar-artifact-mcp`'s
matching `*_to_yaml`.

### `create_{template,element,field,instance}(artifact)`

Creates a new artifact on the server (`POST /{type}`), placed in your home folder. **Writes to the
server.** The top-level `@id` is forced to `null` on submission, so the **server** mints the
identity and returns it in the response — the `@id` you get back is the server's, not anything you
supplied. The body must carry a `version` and `status` (the server requires both; a body from
`cedar-artifact-mcp`'s `*_to_json` already has them — see DESIGN.md).

### `update_{template,element,field,instance}(id, artifact)`

Updates an existing artifact (`PUT /{type}/{id}`). **Writes to the server.** The `id` argument and
the body's `@id` must agree; unlike `create`, nothing is re-minted. Returns the stored artifact as
JSON.

### `delete_{template,element,field,instance}(id)`

Permanently deletes an artifact by IRI (`DELETE /{type}/{id}`). **Destructive and irreversible** —
confirm with the user before calling. Returns a confirmation on success.

### `validate_artifact(artifact)`

Validates an artifact against the CEDAR meta-model using the server's authoritative
`POST /command/validate`. The kind is auto-detected from the JSON `@type`; the artifact is checked
exactly as received. Returns the server's report — `{"validates": true|false, "warnings": [...],
"errors": [...]}`. Read-only: nothing is created. Complements `cedar-artifact-mcp`'s client-side
`validate_*` — this is the server's authoritative verdict.

### `ping(message)`

Echoes `message` back, confirming the MCP server is reachable. Does **not** contact the CEDAR
server (needs no API key) — a pure liveness check.

## Configuration

Set in the MCP client's config (e.g. `~/.claude.json`), never in source or chat:

```json
"cedar-rest": {
  "command": "/usr/bin/java",
  "args": ["-jar", "/path/to/cedar-rest-mcp/target/cedar-rest-mcp-0.1.0-SNAPSHOT-all.jar"],
  "env": {
    "CEDAR_API_KEY": "apiKey <your-key>",
    "CEDAR_BASE_URL": "https://resource.metadatacenter.org"
  }
}
```

- `CEDAR_API_KEY` — required for any live call (bare or `apiKey `-prefixed).
- `CEDAR_BASE_URL` — defaults to production (`https://resource.metadatacenter.org`); point it at a
  local CEDAR stack for development.

## Build

```bash
mvn package          # builds target/cedar-rest-mcp-0.1.0-SNAPSHOT-all.jar (shaded, executable)
mvn test             # unit tests (run against a fake HTTP transport; no live server needed)
mvn verify           # + integration tests, but the live ones are excluded by default
```

### Live integration tests

`CedarLifecycleIT` exercises validate → create → get → delete against a **real** CEDAR server,
using canned templates from the sibling `cedar-artifact-library` checkout, and deletes whatever it
creates. It is tagged `live` and **excluded from the default build** (mirroring
`bioportal-term-mcp`'s `live` pytest marker). Run it on demand:

```bash
CEDAR_API_KEY="apiKey <key>" CEDAR_BASE_URL="https://resource.metadatacenter.org" \
  mvn verify -Plive
```

Without `CEDAR_API_KEY` the live tests self-skip. Each test cleans up after itself (a failed
cleanup fails the test).
