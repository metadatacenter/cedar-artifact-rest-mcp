package org.metadatacenter.cedar.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * What the REST tools need to know about an artifact as it passes to and from the server: which
 * serialization it is written in, which kind it is, how to strip an identifier the caller should
 * not be supplying, and how to read back the one the server assigned.
 *
 * <p>Nothing here reads an artifact into the model. Artifacts travel to and from the server in the
 * serialization the caller used — YAML by default, and CEDAR's JSON-LD when the caller asks for it —
 * and the server reads and writes both, so nothing is gained by converting first: a document that
 * arrives unaltered is a document this MCP cannot damage. Even a sparse instance goes as it was
 * written; the server completes it against its template, since the empty fields its stored JSON has
 * to carry are a requirement of that serialization and have no YAML spelling at all.
 */
final class ArtifactCodec
{
  static final String JSON_LD_ID = "@id";
  static final String YAML_ID = "id";

  // CEDAR JSON-LD type IRIs and keys used to identify an artifact's kind, for validate_artifact.
  private static final String TEMPLATE_TYPE_IRI = "https://schema.metadatacenter.org/core/Template";
  private static final String ELEMENT_TYPE_IRI = "https://schema.metadatacenter.org/core/TemplateElement";
  private static final String FIELD_TYPE_IRI = "https://schema.metadatacenter.org/core/TemplateField";
  private static final String STATIC_FIELD_TYPE_IRI = "https://schema.metadatacenter.org/core/StaticTemplateField";
  private static final String JSON_IS_BASED_ON = "schema:isBasedOn";

  private static final ObjectMapper JACKSON = new ObjectMapper();

  private ArtifactCodec() {}

  /** The serialization {@code text} is written in. */
  static ArtifactFormat formatOf(String text)
  {
    return looksLikeJson(text) ? ArtifactFormat.JSON : ArtifactFormat.YAML;
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

  /**
   * The artifact's kind, read from the YAML {@code type:} discriminator or from the JSON-LD
   * {@code @type}. Used by {@code validate_artifact}, which the caller does not tell which kind it
   * is passing.
   */
  static ArtifactType detectKind(String text)
  {
    return looksLikeJson(text) ? kindFromJsonLdType(asObjectNode(text)) : kindFromYamlType(parseYamlMap(text));
  }

  /**
   * The artifact with its identity left to the server, for a create. The server mints every
   * identifier — an artifact's own and its children's — and refuses a create whose artifact already
   * names itself, so a caller that repeats a fetched artifact to copy it would otherwise be rejected
   * for carrying the original's identity.
   *
   * <p>The two serializations spell "no identifier" differently. JSON says it with an explicit
   * {@code "@id": null}, which the instance endpoint requires and reads as a request for one; YAML
   * has no null to say it with, so the key is dropped, which is the same request. A YAML document
   * that names nothing is returned exactly as it came: the common path leaves the caller's text
   * untouched, and only the copy case is re-serialized.
   */
  static String askServerToMintIdentifier(String text)
  {
    if (looksLikeJson(text)) {
      ObjectNode node = asObjectNode(text);
      node.putNull(JSON_LD_ID);
      return compactJson(node);
    }
    LinkedHashMap<String, Object> map = parseYamlMap(text);
    if (!map.containsKey(YAML_ID))
      return text;
    map.remove(YAML_ID);
    return newYaml().dump(map);
  }

  /**
   * The identifier an artifact carries, read from whichever serialization it arrived in: JSON-LD
   * spells it {@code @id}, YAML spells it {@code id}. A create reads it off the artifact the server
   * answered with, to fetch that artifact back in a form the write could not produce. Null when the
   * document names none, which leaves the caller to answer with what it already holds.
   */
  static String identifierOf(String text)
  {
    if (looksLikeJson(text)) {
      JsonNode id = asObjectNode(text).get(JSON_LD_ID);
      return id == null || !id.isTextual() || id.asText().isBlank() ? null : id.asText();
    }
    Object id = parseYamlMap(text).get(YAML_ID);
    return id == null || String.valueOf(id).isBlank() ? null : String.valueOf(id);
  }

  private static ArtifactType kindFromYamlType(Map<String, Object> map)
  {
    String type = map.get("type") == null ? "" : String.valueOf(map.get("type"));
    return switch (type) {
      case "template" -> ArtifactType.TEMPLATE;
      case "element" -> ArtifactType.ELEMENT;
      case "instance" -> ArtifactType.INSTANCE;
      case "element-instance" -> throw new IllegalArgumentException(
          "the server validates templates, elements, fields and instances; an element instance is "
              + "validated as part of the template instance that carries it");
      case "" -> throw new IllegalArgumentException(
          "the YAML names no artifact kind — it needs a 'type:' key");
      default -> ArtifactType.FIELD;
    };
  }

  private static ArtifactType kindFromJsonLdType(ObjectNode node)
  {
    String typeIri = firstType(node);
    if (typeIri != null) {
      if (TEMPLATE_TYPE_IRI.equals(typeIri)) return ArtifactType.TEMPLATE;
      if (ELEMENT_TYPE_IRI.equals(typeIri)) return ArtifactType.ELEMENT;
      if (FIELD_TYPE_IRI.equals(typeIri) || STATIC_FIELD_TYPE_IRI.equals(typeIri)) return ArtifactType.FIELD;
    }
    if (node.hasNonNull(JSON_IS_BASED_ON)) return ArtifactType.INSTANCE;
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
    dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
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
