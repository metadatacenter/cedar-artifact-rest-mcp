package org.metadatacenter.cedar.rest;

/**
 * A serialization an artifact travels in, on the wire and at the tool boundary. Both are equal
 * serializations of the artifact model; YAML is the one this MCP prefers, because it is an order of
 * magnitude smaller than CEDAR's JSON-LD and is what the rest of the ecosystem authors in.
 *
 * <p>The media type for YAML is the one RFC 9512 registers. CEDAR also answers to
 * {@code application/x-yaml}, the spelling it used before that registration.
 */
public enum ArtifactFormat
{
  YAML("application/yaml"),
  JSON("application/json");

  private final String mediaType;

  ArtifactFormat(String mediaType)
  {
    this.mediaType = mediaType;
  }

  public String mediaType()
  {
    return mediaType;
  }

  /** The format a {@code format} tool argument names; anything but {@code json} means YAML. */
  public static ArtifactFormat fromArgument(String argument)
  {
    return "json".equalsIgnoreCase(argument) ? JSON : YAML;
  }
}
