package io.lionweb.client.delta.messages.queries.participations;

import io.lionweb.client.delta.DeltaProtocolVersion;
import io.lionweb.client.delta.messages.DeltaQuery;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Query sent by a client to establish a new participation in the delta protocol.
 *
 * @see <a href="https://lionweb.io/specification/delta/delta-api.html">LionWeb Delta API
 *     specification</a>
 */
public class SignOnRequest extends DeltaQuery {
  /** The version string of the delta protocol (e.g. "2026.1"). */
  public final @NotNull DeltaProtocolVersion deltaProtocolVersion;

  /** The identifier this client uses to identify itself. */
  public final @NotNull String clientId;

  /** The identifier of the repository the client wants to connect to. */
  public @NotNull String repositoryId;

  public SignOnRequest(
      @NotNull String queryId,
      @NotNull DeltaProtocolVersion deltaProtocolVersion,
      @NotNull String clientId) {
    super(queryId);
    Objects.requireNonNull(deltaProtocolVersion, "deltaProtocolVersion must not be null");
    Objects.requireNonNull(clientId, "clientId must not be null");
    this.deltaProtocolVersion = deltaProtocolVersion;
    this.clientId = clientId;
  }

  @Override
  public String toString() {
    return "SignOnRequest{"
        + "deltaProtocolVersion="
        + deltaProtocolVersion
        + ", clientId='"
        + clientId
        + '\''
        + ", repositoryId='"
        + repositoryId
        + '\''
        + ", queryId='"
        + queryId
        + '\''
        + ", additionalInfos="
        + additionalInfos
        + '}';
  }
}
