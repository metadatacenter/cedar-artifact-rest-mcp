# Design

Principles governing what belongs in `cedar-artifact-rest-mcp`. Read before adding a tool or input.

## Principle 1 — Artifacts only, over one REST API

This MCP wraps exactly one thing: the CEDAR **resource server** REST API, and only its
**artifact** surface — templates, template-elements, template-fields, template-instances. CRUD
plus server-side validation. Everything else the resource server offers — folders, categories,
search, users, groups, permissions, index maintenance — is out of scope (see ROADMAP.md).

## Principle 2 — The server is the system of record

`cedar-artifact-rest-mcp` does not model, mutate, or reason about artifacts, beyond completing a
sparse instance so it can be stored. The CEDAR server owns identity (`@id`), validation, versioning,
and persistence. This MCP is a thin,
honest conduit to it. Artifact *construction* and in-memory validation/conversion live in
`cedar-artifact-mcp`; the two compose (build there, persist here).

## Principle 3 — An artifact travels in the serialization it was written in

The CEDAR resource server reads and writes both YAML and JSON, chosen by `Content-Type` on the way
in and `Accept` on the way out. This MCP uses that directly: a caller's artifact is sent as it was
written, and the server's answer is asked for in the serialization the caller wants back — YAML by
default, JSON when they ask. Nothing is transcoded here, so an artifact that arrives unaltered is
one this MCP cannot damage, and the YAML a caller reads is the server's own rendering rather than a
second implementation of it.

YAML is the default because it is an order of magnitude smaller than CEDAR's JSON-LD while carrying
the same artifact — a difference that decides whether a template fits comfortably in a
conversation. Neither serialization is privileged in the model: the artifact *model* is what is
canonical, and JSON and YAML are equal serializations of it (cedar-artifact-mcp Principle 8).

Nothing is converted, an instance included. A stored instance's JSON must carry every field its
template declares, empty ones included, and YAML has no way to write an empty field — its reader
refuses `{}` and `value: null` alike. The completion belongs to the serialization that needs it, so
the server does it, against the template the instance's `isBasedOn` names. This MCP sends the sparse
YAML as the caller wrote it.

That is why nothing here reads an artifact into the model, and why this MCP carries no
`cedar-artifact-library` dependency: it resolves from Maven Central alone, and its jar is a fifth of
the size it was.

## Principle 4 — Identity is the server's

CEDAR mints every identifier an artifact carries: its own `@id`, one for each child, and the
property IRIs its `@context` maps. This MCP mints none, and neither should its callers.

`create_*` therefore strips the identity an artifact arrives with, in whichever way its
serialization spells "none": a JSON body's `@id` is set to `null`, which is what the instance
endpoint requires and reads as a request for one, and a YAML body's `id:` key is dropped, since YAML
has no null to write there. Stripping rather than refusing is what lets a fetched artifact be
repeated to copy it. `update_*` keeps the `@id` — it identifies the artifact being replaced, and the
path `{id}` and the body must agree.

The null on the JSON side is not valid JSON-LD: `@id` takes an IRI, and a node object with no `@id`
is a blank node, which is what an artifact without an identity is. Omitting the key would be the
correct form, and the create endpoint would take it — it accepts an `@id` that is null *or* missing.
What refuses it is CEDAR's own meta-schema, which marks `@id` required and types it
`["string", "null"]`; the model carries a second `@id` content schema,
`jsonLDIDFieldContentWithNull`, for exactly this. One schema validates both a create request and a
stored artifact, so it cannot say "absent while being authored, present once stored", and the null
is where those two roles were reconciled.

The null is therefore request-only, and a symptom of that schema rather than a decision of its own.
No stored artifact carries it and nothing reads one back. It is long-standing and not something to
fix in passing; do not copy the shape anywhere it would outlive a request.

Nested children are sent exactly as the artifact carries them. The server replaces them regardless:
a non-verbatim write mints a child identifier and a property IRI for every child, whatever arrived.

## Principle 5 — Errors are content

A non-2xx response is returned as a `CallToolResult` with `isError=true` carrying the HTTP status
and the server's response body — not an MCP protocol error. The LLM can read it and react. The
same applies to parse/convert failures.

## Principle 6 — Writes are real; deletes are dangerous

`create` / `update` / `delete` mutate a live server. `delete_*` is **destructive and
irreversible**; its description instructs confirming with the user first. The MCP can't enforce
confirmation, so the tool surface makes the danger unmistakable. Reads and `validate_artifact` are
side-effect-free.

## Principle 7 — Stateless; config and secrets from the environment

No session state — every call carries its IRIs and artifact. The base URL (`CEDAR_BASE_URL`) and
API key (`CEDAR_API_KEY`) come from the environment (set in the MCP client's config); the key is
never logged or echoed, only placed in the `Authorization` header.

## Principle 8 — The HTTP boundary is a seam

All network I/O goes through the `CedarHttp` interface. The production implementation
(`DefaultCedarHttp`) uses `java.net.http`; tests inject a fake, so handlers, the negotiated
serializations, the create identity rule, path encoding, the re-read after an update, and error
handling are all verified without a live server.

## Note — a sparse instance is completed by the server

The instances `cedar-artifact-mcp` produces are sparse, and a stored instance's JSON has to carry
every field its template declares. The server closes that gap on the YAML write path: it resolves
`isBasedOn`, reads the template, and materializes the empty slots before validating. A caller hands
over the lean YAML and nothing else, and reads come back lean again.

The completion is the YAML path's alone. A JSON instance is stored as it was sent, and a field it
omits is still refused — the two bodies are the same document, and the serialization is the only
thing that says whether an absent field means "empty" or "deleted".

## Note — the server rewrites `title` and `description` on persist

A CEDAR artifact's JSON-Schema `title` and `description` (distinct from `schema:name` /
`schema:description`) are **not authoritative on the way in** — the CEDAR server overwrites them
when it stores the artifact, stamping a description like
`"<name> template schema generated by the CEDAR Template Editor <version>"`. What reaches it is
whatever produced the JSON upstream (`cedar-artifact-mcp`'s `*_to_json` synthesizes
`"… generated by the CEDAR Artifact Library"`), so don't expect the `title`/`description` you
submit to survive a round trip. `schema:name` and `schema:description` are preserved; only the
JSON-Schema `title`/`description` are rewritten.

## Note — `version` and `status` on a write

The CEDAR server demands that a persisted schema artifact carry a version (`pav:version`) and a
status (`bibo:status`), and it rejects a JSON body that omits either — the server-side root cause,
that it demands these rather than defaulting them, is filed as
[cedar-resource-server#92](https://github.com/metadatacenter/cedar-resource-server/issues/92). A
JSON artifact assembled by hand must therefore carry both. In practice they are there:
`cedar-artifact-mcp` renders them from the artifact, and the library defaults a top-level artifact's
version and status to `0.0.1` and `draft`.

A YAML body needs neither. The server reads it into the model, where the same defaults apply, so a
minimal template — a name and its children — is accepted and comes back stamped `0.0.1` / `draft`.
