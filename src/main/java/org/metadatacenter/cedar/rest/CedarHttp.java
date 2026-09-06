package org.metadatacenter.cedar.rest;

/**
 * The HTTP seam to the CEDAR resource server. An interface so tool handlers can be unit-tested
 * against a fake transport without a live server (the production implementation is
 * {@link DefaultCedarHttp}).
 */
public interface CedarHttp
{
  /**
   * Send a request to the resource server.
   *
   * @param method       HTTP method (GET / POST / PUT / DELETE)
   * @param pathAndQuery path (and any query string) relative to the base URL, e.g.
   *                     {@code /templates/https%3A%2F%2F...}; the implementation prepends the base URL
   * @param body         request body, or {@code null} for no body
   * @param bodyFormat   the serialization {@code body} is written in, naming its {@code Content-Type};
   *                     ignored when there is no body
   * @param accept       the serialization to ask for in the response
   * @param ifMatch      the entity tag a write asserts it is replacing, sent as {@code If-Match}, or
   *                     {@code null} to assert nothing. CEDAR requires one to update or delete an
   *                     artifact, and answers 428 without it.
   * @return the status, body and entity tag of the response
   */
  CedarResponse request(String method, String pathAndQuery, String body,
      ArtifactFormat bodyFormat, ArtifactFormat accept, String ifMatch);

  /** A request that asserts nothing about the artifact's revision, which is every read. */
  default CedarResponse request(String method, String pathAndQuery, String body,
      ArtifactFormat bodyFormat, ArtifactFormat accept)
  {
    return request(method, pathAndQuery, body, bodyFormat, accept, null);
  }

  /**
   * Status, body and entity tag of a resource-server response.
   *
   * <p>The tag is what a later write sends back as {@code If-Match}. CEDAR issues a different one per
   * representation — {@code "3"} for JSON, {@code "3-yaml"}, {@code "3-yaml-compact"} — and reads the
   * revision out of whichever it is given, so the tag from any read satisfies the precondition.
   */
  record CedarResponse(int status, String body, String etag)
  {
    /** A response whose entity tag is unknown or absent, which is what a fake transport supplies. */
    public CedarResponse(int status, String body)
    {
      this(status, body, null);
    }

    public boolean isSuccess() { return status >= 200 && status < 300; }
  }
}
