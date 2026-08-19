# cedar-artifact-rest-mcp

An MCP server that manages CEDAR **artifacts** — templates, elements, fields, and instances —
through the CEDAR [**resource-server REST API**](https://resource.metadatacenter.org/api/). It is the I/O counterpart to
[`cedar-artifact-mcp`](../cedar-artifact-mcp). That one builds, converts, and validates artifacts
in memory; this one persists them to, and fetches them from, a live CEDAR server.

It deliberately covers **only the artifact corner** of that REST API — create / fetch / update /
delete / validate for templates, elements, fields, and instances. The rest of the CEDAR REST
surface — folders, search, users, groups, permissions, categories — is **out of scope** (hence the
name; see [ROADMAP.md](./ROADMAP.md)). It is not, and is not meant to be, a full CEDAR REST client.

See [DESIGN.md](./DESIGN.md) for the principles and [ROADMAP.md](./ROADMAP.md) for scope and
deferred work.

## Example workflow

A typical session looks like the following — natural-language prompts the user gives the LLM,
which it translates into REST MCP tool calls against a live CEDAR server. This MCP is the
**persistence half** of the pipeline — author and shape an artifact in memory with
[`cedar-artifact-mcp`](../cedar-artifact-mcp), then hand it here to validate, save, fetch, update,
or remove it.

Artifacts travel as YAML. The CEDAR server reads and writes both YAML and JSON, so a body goes to
it exactly as the caller wrote it and a response comes back in the serialization the caller asked
for — YAML unless they pass `format: json`. Nothing is converted along the way, which is why the
YAML below is what actually crossed the wire rather than a rendering of something else.

Assume the LLM already has a Patient Study template in hand, authored with `cedar-artifact-mcp`
(shown as the compact YAML the user sees). It names no artifact yet: CEDAR assigns identity when
the template is created.

```yaml
type: template
name: "Patient Study"
children:
  - key: "Patient Name"
    type: text-field
    name: "Patient Name"
  - key: "Age"
    type: numeric-field
    name: "Age"
    datatype: xsd:int
```

*Check it against CEDAR's validator before I save it.*

```json
{"validates":"true","warnings":[],"errors":[]}
```

`validate_artifact` posts the template to the server's authoritative validator and returns its
report. Nothing is created — it is a dry run, usable on any artifact including ones pulled from
elsewhere.

*Save it to CEDAR.*

```yaml
type: template
name: "Patient Study"
id: "https://repo.metadatacenter.org/templates/74533fe4-d182-419e-bde5-30829f48dc41"
status: draft
version: 0.0.1
modelVersion: 1.6.0
createdOn: "2026-08-18T18:49:15-07:00"
createdBy: "https://metadatacenter.org/users/0e97ec85-77a9-434c-8549-33f9eae22608"
modifiedOn: "2026-08-18T18:49:15-07:00"
modifiedBy: "https://metadatacenter.org/users/0e97ec85-77a9-434c-8549-33f9eae22608"
children:
  - key: "Patient Name"
    type: text-field
    name: "Patient Name"
    id: "https://repo.metadatacenter.org/template-fields/f07598a6-1d27-4531-add4-06a3e75d15ae"
    status: draft
    version: 0.0.1
    modelVersion: 1.6.0
    createdOn: "2026-08-18T18:49:15-07:00"
    createdBy: "https://metadatacenter.org/users/0e97ec85-77a9-434c-8549-33f9eae22608"
    modifiedOn: "2026-08-18T18:49:15-07:00"
    modifiedBy: "https://metadatacenter.org/users/0e97ec85-77a9-434c-8549-33f9eae22608"
    configuration:
      propertyIri: "https://schema.metadatacenter.org/properties/a767fc24-6d7a-478a-a02a-e362562040fa"
  - key: "Age"
    type: numeric-field
    name: "Age"
    id: "https://repo.metadatacenter.org/template-fields/2228a85a-2e05-4b11-b2d1-1c1dc85aed58"
    ...
```

`create_template` returns what CEDAR stored, in the full form: the template's identity, one for
each child, a property IRI per child, provenance, and the `0.0.1` / `draft` the server supplied
because the template named neither. Everything here that identifies something was minted by the
server — an artifact reaches it carrying no identity, and any it did carry is stripped before the
write.

*Fetch it back.*

`get_template` with that IRI returns the same document. It is the exchange form, so it threads
straight back into `update_template` without losing provenance, version, or status; pass
`format: json` when something downstream needs CEDAR's JSON-LD instead.

*Create an instance called Patient Study for Alice, with Patient Name = Alice and Age = 30.*

The LLM builds the instance with `cedar-artifact-mcp` against the stored template — sparse, naming
only what it holds:

```yaml
type: instance
name: "Patient Study"
isBasedOn: "https://repo.metadatacenter.org/templates/74533fe4-d182-419e-bde5-30829f48dc41"
children:
  Patient Name:
    value: "Alice"
  Age:
    datatype: xsd:int
    value: "30"
```

and `create_instance` writes it:

```yaml
type: instance
name: "Patient Study"
id: "https://repo.metadatacenter.org/template-instances/cba3c72a-ff4f-40ff-bf54-411d259d63cc"
isBasedOn: "https://repo.metadatacenter.org/templates/74533fe4-d182-419e-bde5-30829f48dc41"
createdOn: "2026-08-18T18:49:15-07:00"
createdBy: "https://metadatacenter.org/users/0e97ec85-77a9-434c-8549-33f9eae22608"
modifiedOn: "2026-08-18T18:49:15-07:00"
modifiedBy: "https://metadatacenter.org/users/0e97ec85-77a9-434c-8549-33f9eae22608"
children:
  Patient Name:
    value: "Alice"
  Age:
    datatype: xsd:int
    value: "30"
```

The server completed the instance against its template before storing it — a stored instance's JSON
has to carry every field the template declares, and YAML cannot write an empty one — and renders it
back sparse on the way out. A caller sees neither step.

*Delete the template.*

```
Deleted template: https://repo.metadatacenter.org/templates/74533fe4-d182-419e-bde5-30829f48dc41
```

`delete_template` is irreversible; the tool description tells the LLM to confirm with the user
first.

## Tools

Each tool is a thin wrapper over one CEDAR **resource-server** REST endpoint — `get` / `create` /
`update` / `delete` for each of the four artifact kinds, plus server-side validation. The four
kinds differ only by endpoint path, so the tools are generated per kind and behave identically
within an operation; they're documented once per operation below. An artifact travels in the
serialization it was written in and comes back in the one the caller asked for (see
[DESIGN.md](./DESIGN.md) Principle 3): YAML by default, JSON with `format: json`. A non-2xx server
response is surfaced as an error result carrying the status and body (errors are content, never
thrown).

| Group | Tools |
|---|---|
| Fetch | `get_template` · `get_element` · `get_field` · `get_instance` |
| Create | `create_template` · `create_element` · `create_field` · `create_instance` |
| Update | `update_template` · `update_element` · `update_field` · `update_instance` |
| Delete | `delete_template` · `delete_element` · `delete_field` · `delete_instance` |
| Validate | `validate_artifact` |
| Diagnostics | `ping` |

**Conventions.** Artifacts are addressed by `@id` — the full CEDAR IRI; URL-encoding into the
request path is handled for you, so pass the plain IRI. Discovery (search, folder listing) is
**out of scope** — you operate by IRI: fetch what you can name, or what a `create` just returned.

### `get_{template,element,field,instance}(id)`

Fetches an artifact from the CEDAR server by its `@id` IRI (`GET /{type}/{id}`). Returns the
artifact as YAML — the exchange form, an order of magnitude smaller than CEDAR's JSON-LD and
carrying every field, so it threads back into `update_*` unaltered. Pass `format: json` for the
JSON-LD.

### `create_{template,element,field,instance}(artifact)`

Creates a new artifact on the server (`POST /{type}`), placed in your home folder. **Writes to the
server.** Identity is stripped from the body, so the **server** mints it — the artifact's own IRI,
its children's, and a property IRI per child — and returns the stored artifact carrying all of
them. A YAML body needs no `version` or `status`; the server defaults them to `0.0.1` and `draft`.
A JSON body must carry both (see DESIGN.md). A sparse instance is completed by the server, so lean
YAML is all a caller has to write.

### `update_{template,element,field,instance}(id, artifact)`

Updates an existing artifact (`PUT /{type}/{id}`). **Writes to the server.** The `id` argument and
the body's `@id` must agree; unlike `create`, the artifact's own identity is kept. Returns the
stored artifact, re-read after the write — the `PUT` itself answers with the artifact's folder
record rather than the artifact.

### `delete_{template,element,field,instance}(id)`

Permanently deletes an artifact by IRI (`DELETE /{type}/{id}`). **Destructive and irreversible** —
confirm with the user before calling. Returns a confirmation on success.

### `validate_artifact(artifact)`

Validates an artifact against the CEDAR meta-model using the server's authoritative
`POST /command/validate`. The kind is read from the YAML `type:` discriminator or the JSON
`@type`, and the artifact is checked as written. Returns the server's report — `{"validates": true|false, "warnings": [...],
"errors": [...]}`. Read-only: nothing is created. Complements `cedar-artifact-mcp`'s client-side
`validate_*` — this is the server's authoritative verdict.

### `ping(message)`

Echoes `message` back, confirming the MCP server is reachable. Does **not** contact the CEDAR
server (needs no API key) — a pure liveness check.

## Configuration

Set in the MCP client's config (e.g. `~/.claude.json`), never in source or chat:

```json
"cedar-artifact-rest": {
  "command": "/usr/bin/java",
  "args": ["-jar", "/path/to/cedar-artifact-rest-mcp/target/cedar-artifact-rest-mcp-0.1.0-SNAPSHOT-all.jar"],
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
mvn package          # builds target/cedar-artifact-rest-mcp-0.1.0-SNAPSHOT-all.jar (shaded, executable)
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
