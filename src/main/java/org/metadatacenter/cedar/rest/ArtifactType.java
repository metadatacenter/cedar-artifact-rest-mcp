package org.metadatacenter.cedar.rest;

/**
 * The four CEDAR artifact kinds this MCP manages, with the bits that vary between them: the
 * resource-server path segment, the tool-name noun, and the {@code resource_type} the server's
 * validate command expects.
 */
public enum ArtifactType
{
  TEMPLATE("templates", "template", "template"),
  ELEMENT("template-elements", "element", "element"),
  FIELD("template-fields", "field", "field"),
  INSTANCE("template-instances", "instance", "instance");

  /** REST path segment, e.g. {@code templates} in {@code /templates/{id}}. */
  public final String pathSegment;
  /** Tool-name noun, e.g. {@code template} in {@code get_template}. */
  public final String noun;
  /** {@code resource_type} query value for {@code POST /artifact-validations/{resource_type}}. */
  public final String validateResourceType;

  ArtifactType(String pathSegment, String noun, String validateResourceType)
  {
    this.pathSegment = pathSegment;
    this.noun = noun;
    this.validateResourceType = validateResourceType;
  }
}
