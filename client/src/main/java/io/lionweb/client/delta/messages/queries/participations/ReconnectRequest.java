package io.lionweb.client.delta.messages.queries.participations;

import io.lionweb.client.delta.DeltaProtocolVersion;
import io.lionweb.client.delta.messages.DeltaQuery;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Query sent by a client to resume an interrupted participation, replaying missed events.
 *
 * @see <a href="https://lionweb.io/specification/delta/delta-api.html">LionWeb Delta API
 *     specification</a>
 */
public class ReconnectRequest extends DeltaQuery {
  /** The version of the delta protocol (e.g. "2026.1"). */
  public DeltaProtocolVersion deltaProtocolVersion;

  /** The identifier this client uses to identify itself. */
  public String clientId;

  /** The identifier of the repository the client wants to reconnect to. */
  public String repositoryId;

  /** The participation ID from the previous session. */
  public final @NotNull String participationId;

  /** The sequence number of the last event the client received. */
  public final long lastReceivedSequenceNumber;

  public ReconnectRequest(
      @NotNull String queryId, @NotNull String participationId, long lastReceivedSequenceNumber) {
    super(queryId);
    Objects.requireNonNull(participationId, "participationId must not be null");
    this.participationId = participationId;
    this.lastReceivedSequenceNumber = lastReceivedSequenceNumber;
  }

  @Override
  public String toString() {
    return "ReconnectRequest{"
        + "deltaProtocolVersion="
        + deltaProtocolVersion
        + ", clientId='"
        + clientId
        + '\''
        + ", repositoryId='"
        + repositoryId
        + '\''
        + ", participationId='"
        + participationId
        + '\''
        + ", lastReceivedSequenceNumber="
        + lastReceivedSequenceNumber
        + ", queryId='"
        + queryId
        + '\''
        + ", additionalInfos="
        + additionalInfos
        + '}';
  }
}
