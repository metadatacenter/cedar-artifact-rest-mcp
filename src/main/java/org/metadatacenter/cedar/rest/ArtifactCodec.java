package org.metadatacenter.cedar.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.artifacts.model.core.Artifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.InstanceInflater;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Artifact codec for the REST tools. The CEDAR server's wire format is JSON, so artifacts are sent
 * and returned as JSON. Callers, however, supply an artifact as the compact
 * YAML the rest of the ecosystem trades in (CEDAR JSON is large enough that handing it
 * to an LLM is impractical), with JSON also accepted — so this codec accepts both: YAML is read
 * into the artifact model with {@code cedar-artifact-library} and rendered to JSON before it goes
 * to the server.
 * On the way back, a fetched artifact is rendered to YAML by default (see {@link #toYaml}) — the
 * compact exchange form — and only returned as JSON when the caller explicitly asks for it.
 */
final class ArtifactCodec
{
  static final String JSON_LD_ID = "@id";

  // CEDAR JSON-LD type IRIs and keys used to identify an artifact's kind, for validate_artifact.
  private static final String TEMPLATE_TYPE_IRI = "https://schema.metadatacenter.org/core/Template";
  private static final String ELEMENT_TYPE_IRI = "https://schema.metadatacenter.org/core/TemplateElement";
  private static final String FIELD_TYPE_IRI = "https://schema.metadatacenter.org/core/TemplateField";
  private static final String STATIC_FIELD_TYPE_IRI = "https://schema.metadatacenter.org/core/StaticTemplateField";
  private static final String SCHEMA_IS_BASED_ON = "schema:isBasedOn";

  private static final ObjectMapper JACKSON = new ObjectMapper();
  // Compact-mode reader: accepts the lean authoring YAML (an absent modelVersion defaults).
  private static final YamlArtifactReader YAML_READER = new YamlArtifactReader(true);
  private static final JsonArtifactRenderer JSON_RENDERER = new JsonArtifactRenderer();
  // Reads CEDAR JSON (what the server serves) into the artifact model for YAML rendering.
  private static final JsonArtifactReader JSON_READER = new JsonArtifactReader();

  private ArtifactCodec() {}

  /**
   * Render a fetched artifact — CEDAR JSON straight from the server — as YAML. The kind
   * is supplied by the caller (each REST tool knows its own {@link ArtifactType}), so no
   * {@code @type} sniffing is needed. The YAML is the expanded, lossless exchange form: an order of
   * magnitude smaller than the JSON yet carrying every field (including provenance, version, and
   * status), so it round-trips back through {@code update_*} without losing anything.
   */
  static String toYaml(ArtifactType type, ObjectNode node)
  {
    Artifact artifact = switch (type) {
      case TEMPLATE -> JSON_READER.readTemplateSchemaArtifact(node);
      case ELEMENT -> JSON_READER.readElementSchemaArtifact(node);
      case FIELD -> JSON_READER.readFieldSchemaArtifact(node);
      case INSTANCE -> JSON_READER.readTemplateInstanceArtifact(node);
    };
    return YamlSerializer.getYAML(artifact, false, false);
  }

  /** The template IRI an instance is based on ({@code schema:isBasedOn}), or null if absent. */
  static String isBasedOn(ObjectNode instanceJson)
  {
    JsonNode node = instanceJson.path(SCHEMA_IS_BASED_ON);
    return node.isTextual() ? node.asText() : null;
  }

  /**
   * Inflate a (possibly sparse) instance against its template, returning the complete instance
   * JSON. A YAML-sourced instance omits empty fields, but the server's validator requires every
   * template property present; inflation materializes the missing empty slots. Pure — the caller
   * supplies the template JSON (the REST tools fetch it from the server).
   */
  static ObjectNode inflateInstance(ObjectNode templateJson, ObjectNode instanceJson)
  {
    TemplateSchemaArtifact template = JSON_READER.readTemplateSchemaArtifact(templateJson);
    TemplateInstanceArtifact instance = JSON_READER.readTemplateInstanceArtifact(instanceJson);
    return JSON_RENDERER.renderTemplateInstanceArtifact(InstanceInflater.inflate(template, instance));
  }

  /**
   * Parse an incoming artifact — YAML or JSON — into a CEDAR JSON {@code ObjectNode}.
   * JSON is parsed as-is; YAML is read into the artifact model and re-rendered to JSON, with the
   * kind taken from the YAML {@code type:} discriminator (anything that isn't template / element /
   * instance / element-instance is a field kind).
   */
  static ObjectNode toObjectNode(String text)
  {
    if (looksLikeJson(text))
      return asObjectNode(text);

    LinkedHashMap<String, Object> map = parseYamlMap(text);
    String type = map.get("type") == null ? "" : String.valueOf(map.get("type"));
    return switch (type) {
      case "template" -> JSON_RENDERER.renderTemplateSchemaArtifact(YAML_READER.readTemplateSchemaArtifact(map));
      case "element" -> JSON_RENDERER.renderElementSchemaArtifact(YAML_READER.readElementSchemaArtifact(map));
      case "instance" -> JSON_RENDERER.renderTemplateInstanceArtifact(YAML_READER.readTemplateInstanceArtifact(map));
      case "element-instance" -> JSON_RENDERER.renderElementInstanceArtifact(YAML_READER.readElementInstanceArtifact(map));
      default -> JSON_RENDERER.renderFieldSchemaArtifact(YAML_READER.readFieldSchemaArtifact(map));
    };
  }

  /**
   * Force the top-level {@code @id} to JSON {@code null} for a create: the server assigns the real
   * identity and returns it. Overwrites whatever the caller's artifact carried.
   */
  static void nullifyTopLevelId(ObjectNode node)
  {
    node.putNull(JSON_LD_ID);
  }

  /** An artifact detected for validation: its kind and the JSON body to send to the server. */
  record Detected(ArtifactType type, String json) {}

  /**
   * Normalize an artifact (YAML or JSON) for {@code /command/validate}: YAML is converted to JSON
   * first; JSON is validated exactly as received. Either way the kind is detected from
   * the resulting {@code @type}.
   */
  static Detected forValidation(String text)
  {
    if (looksLikeJson(text)) {
      ObjectNode node = asObjectNode(text);
      return new Detected(detectFromJson(node), text);
    }
    ObjectNode node = toObjectNode(text);
    return new Detected(detectFromJson(node), compactJson(node));
  }

  private static ArtifactType detectFromJson(ObjectNode node)
  {
    String typeIri = firstType(node);
    if (typeIri != null) {
      if (TEMPLATE_TYPE_IRI.equals(typeIri)) return ArtifactType.TEMPLATE;
      if (ELEMENT_TYPE_IRI.equals(typeIri)) return ArtifactType.ELEMENT;
      if (FIELD_TYPE_IRI.equals(typeIri) || STATIC_FIELD_TYPE_IRI.equals(typeIri)) return ArtifactType.FIELD;
    }
    if (node.hasNonNull(SCHEMA_IS_BASED_ON)) return ArtifactType.INSTANCE;
    throw new IllegalArgumentException(
        "could not determine artifact kind from @type — pass a recognizable CEDAR artifact");
  }

  private static String firstType(ObjectNode node)
  {
    JsonNode typeNode = node.path("@type");
    if (typeNode.isTextual())
      return typeNode.asText();
    if (typeNode.isArray() && typeNode.size() >= 1 && typeNode.get(0).isTextual())
      return typeNode.get(0).asText();
    return null;
  }

  /** Whether {@code text} is JSON (vs YAML); used to pick the parse path. */
  static boolean looksLikeJson(String text)
  {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) continue;
      return c == '{';
    }
    return false;
  }

  static ObjectNode asObjectNode(String text)
  {
    try {
      JsonNode node = JACKSON.readTree(text);
      if (!(node instanceof ObjectNode objectNode))
        throw new IllegalArgumentException("expected a JSON object, got " + node.getNodeType());
      return objectNode;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON parse failed: " + e.getOriginalMessage(), e);
    }
  }

  static String prettyJson(JsonNode node)
  {
    try {
      return JACKSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON serialize failed: " + e.getMessage(), e);
    }
  }

  static String compactJson(JsonNode node)
  {
    try {
      return JACKSON.writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON serialize failed: " + e.getMessage(), e);
    }
  }

  // ---------------------------------------------------------------------
  // YAML parsing — a SnakeYAML loader that does NOT resolve date-like scalars to timestamps,
  // so temporal field values stay strings (matching cedar-artifact-mcp's exchange parser).
  // ---------------------------------------------------------------------

  private static LinkedHashMap<String, Object> parseYamlMap(String yamlText)
  {
    Object parsed = newYaml().load(yamlText);
    if (!(parsed instanceof Map<?, ?>))
      throw new IllegalArgumentException("YAML must parse to a mapping at the top level (got "
          + (parsed == null ? "null" : parsed.getClass().getSimpleName()) + ")");
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet())
      map.put(String.valueOf(entry.getKey()), entry.getValue());
    return map;
  }

  private static Yaml newYaml()
  {
    LoaderOptions loaderOptions = new LoaderOptions();
    DumperOptions dumperOptions = new DumperOptions();
    return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions),
        dumperOptions, loaderOptions, new NoTimestampResolver());
  }

  private static final class NoTimestampResolver extends Resolver
  {
    @Override protected void addImplicitResolvers()
    {
      addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
      addImplicitResolver(Tag.INT, INT, "-+0123456789");
      addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
      addImplicitResolver(Tag.MERGE, MERGE, "<");
      addImplicitResolver(Tag.NULL, NULL, "~nN\0");
      addImplicitResolver(Tag.NULL, EMPTY, null);
      // Tag.TIMESTAMP intentionally not registered — keep date-like scalars as strings.
    }
  }
}
