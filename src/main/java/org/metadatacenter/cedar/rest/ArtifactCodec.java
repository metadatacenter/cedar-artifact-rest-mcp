package org.metadatacenter.cedar.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.regex.Pattern;

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
    return looksLikeJson(text) ? kindFromJsonLdType(asObjectNode(text)) : kindFromYamlType(yamlScalar(text, "type"));
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
    return withoutYamlIdentifier(text);
  }

  /**
   * The same document with its YAML identifier removed; JSON, and a document naming nothing, travel
   * unchanged.
   *
   * <p>A PUT names the artifact in its path, so the body's id only repeats it. That repetition is
   * also in the way: CEDAR refuses a YAML body carrying an id without the system-recorded keys
   * beside it, and that is exactly the compact read-only form every default read returns. The same
   * body without the id is the minimal form, which CEDAR accepts. JSON is not read through that
   * rule and is left alone.
   */
  static String withoutYamlIdentifier(String text)
  {
    if (looksLikeJson(text))
      return text;
    MappingNode root = yamlRoot(text);
    if (!root.getValue().removeIf(ArtifactCodec::namesTheIdentifier))
      return text;
    StringWriter written = new StringWriter();
    newYaml().serialize(root, written);
    return written.toString();
  }

  private static boolean namesTheIdentifier(NodeTuple entry)
  {
    return entry.getKeyNode() instanceof ScalarNode key && YAML_ID.equals(key.getValue());
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
    String id = yamlScalar(text, YAML_ID);
    return id == null || id.isBlank() ? null : id;
  }

  private static ArtifactType kindFromYamlType(String type)
  {
    return switch (type == null ? "" : type) {
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
  // YAML parsing. The server reads YAML with cedar-artifact-library's ArtifactSourceParser, which
  // this MCP does not depend on, so the same YAML 1.2 core scalar rules are stated here. A document
  // is composed, never constructed: the type and identifier are read as the text the document
  // holds, and a document written out again keeps each scalar's text and the type YAML 1.2 gives
  // it, for the server to read by its own rules.
  // ---------------------------------------------------------------------

  /** The text of a top-level scalar, or null when the key is absent, null or not a scalar. */
  private static String yamlScalar(String yamlText, String key)
  {
    for (NodeTuple entry : yamlRoot(yamlText).getValue())
      if (entry.getKeyNode() instanceof ScalarNode name && key.equals(name.getValue()))
        return entry.getValueNode() instanceof ScalarNode value && !Tag.NULL.equals(value.getTag())
            ? value.getValue()
            : null;
    return null;
  }

  private static MappingNode yamlRoot(String yamlText)
  {
    Node root = newYaml().compose(new StringReader(yamlText));
    if (!(root instanceof MappingNode mapping))
      throw new IllegalArgumentException("YAML must parse to a mapping at the top level (got "
          + (root == null ? "null" : root.getNodeId()) + ")");
    return mapping;
  }

  private static Yaml newYaml()
  {
    LoaderOptions loaderOptions = new LoaderOptions();
    DumperOptions dumperOptions = new DumperOptions();
    dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions),
        dumperOptions, loaderOptions, new CoreResolver());
  }

  /** The YAML 1.2 core schema, as cedar-artifact-library's ArtifactSourceParser resolves it. */
  private static final class CoreResolver extends Resolver
  {
    @Override protected void addImplicitResolvers()
    {
      addImplicitResolver(Tag.NULL, Pattern.compile("^(?:~|null|Null|NULL|)$"), "~nN\0");
      addImplicitResolver(Tag.BOOL, Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"), "tTfF");
      addImplicitResolver(Tag.INT, Pattern.compile("^(?:[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+)$"), "-+0123456789");
      addImplicitResolver(Tag.FLOAT, Pattern.compile(
          "^(?:[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?"
          + "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$"), "-+0123456789.");
    }
  }
}
