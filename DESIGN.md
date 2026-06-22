# Design

Principles governing what belongs in `cedar-artifact-rest-mcp`. Read before adding a tool or input.

## Principle 1 — Artifacts only, over one REST API

This MCP wraps exactly one thing: the CEDAR **resource server** REST API, and only its
**artifact** surface — templates, template-elements, template-fields, template-instances. CRUD
plus server-side validation. Everything else the resource server offers — folders, categories,
search, users, groups, permissions, index maintenance — is out of scope (see ROADMAP.md).

## Principle 2 — The server is the system of record

`cedar-artifact-rest-mcp` does not model, mutate, or reason about artifacts beyond format conversion. The
CEDAR server owns identity (`@id`), validation, versioning, and persistence. This MCP is a thin,
honest conduit to it. Artifact *construction* and in-memory validation/conversion live in
`cedar-artifact-mcp`; the two compose (build there, persist here).

## Principle 3 — JSON on the wire, JSON at the boundary

This MCP goes through JSON on the wire — artifacts are sent as JSON and the JSON the server serves
is read back, end to end. (The CEDAR server now accepts and returns both YAML and JSON; this MCP
still travels over JSON.) It does **not** convert to or from YAML, and carries no dependency on
`cedar-artifact-library`. JSON here is just one serialization, not a privileged
representation: the artifact *model* is what's canonical, and JSON and YAML are equal
serializations of it (cedar-artifact-mcp Principle 8) — JSON is the serialization this conduit
happens to use on the wire, and the server, validator, and meta-schema all speak it. CEDAR artifacts also travel as
compact YAML (the human-friendly serialization), and converting between that and the server's JSON
is `cedar-artifact-mcp`'s job: its `*_to_json` and
`*_to_yaml` tools exist for exactly this boundary, and the orchestrating LLM runs them on either
side of a REST call. Conversion lived here once (reusing the library); folding it back into the
single MCP that already owns it removed a duplicated copy of the readers/renderers and let this MCP
resolve entirely from Maven Central. A caller
that passes YAML is redirected to `cedar-artifact-mcp`'s `*_to_json` rather than silently parsed.

## Principle 4 — `@id` on create vs update

`create_*` forces the top-level `@id` to JSON `null` so the **server** assigns the identity (and
returns it). It overwrites whatever the caller's artifact carried — notably an `@id` minted by
`cedar-artifact-mcp`. `update_*` preserves the `@id` (it identifies the artifact; the path `{id}`
and body `@id` must agree).

Only the **top-level** `@id` is nulled. Nested element/field `@id`s are submitted exactly as the
artifact carries them — this is the correct behaviour: the server assigns the artifact's own
identity and accepts whatever the nested children carry. (Artifacts produced by `cedar-artifact-mcp`
are child-`@id`-less anyway — it mints a top-level `@id` only — so the common path submits no
nested `@id`s to begin with.)

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
(`DefaultCedarHttp`) uses `java.net.http`; tests inject a fake, so handlers, the codec, the
`@id`-null rule, path encoding, and error handling are all verified without a live server.

## Note — instances must be complete before create/update

The CEDAR server requires an instance to carry every template field (the template's JSON Schema
marks them required). A *sparse* instance (the form `cedar-artifact-mcp` produces) will be
rejected. Complete it first — e.g. `cedar-artifact-mcp`'s `instance_to_json` given the template —
then create/update it here. `cedar-artifact-rest-mcp` does not fetch the template to inflate (it has no
template on hand); revisit if that ergonomic becomes worth the coupling.

## Note — the server rewrites `title` and `description` on persist

A CEDAR artifact's JSON-Schema `title` and `description` (distinct from `schema:name` /
`schema:description`) are **not authoritative on the way in** — the CEDAR server overwrites them
when it stores the artifact, stamping a description like
`"<name> template schema generated by the CEDAR Template Editor <version>"`. What reaches it is
whatever produced the JSON upstream (`cedar-artifact-mcp`'s `*_to_json` synthesizes
`"… generated by the CEDAR Artifact Library"`), so don't expect the `title`/`description` you
submit to survive a round trip. `schema:name` and `schema:description` are preserved; only the
JSON-Schema `title`/`description` are rewritten.

## Note — the server requires `version` and `status`

The CEDAR server demands that a persisted artifact carry a version (`pav:version`) and a status
(`bibo:status`); an artifact missing either is rejected on `create`/`update`.

This MCP sends the JSON it is given **untouched**, so the version/status must already be present in
that JSON. In practice they are: `cedar-artifact-mcp`'s `*_to_json` renders them from the artifact,
and the library defaults a top-level artifact's `version`/`status` to `0.0.1` / `draft` when
absent — so a JSON body produced by the normal authoring path always carries them, with nothing to
set by hand. A JSON artifact assembled by other means must include them itself.

The server-side root cause — that it *demands* these rather than defaulting them — is filed as
[cedar-resource-server#92](https://github.com/metadatacenter/cedar-resource-server/issues/92).
