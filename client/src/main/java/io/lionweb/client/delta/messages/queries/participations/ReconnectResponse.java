package io.lionweb.client.delta.messages.queries.participations;

import io.lionweb.client.delta.messages.DeltaQueryResponse;
import org.jetbrains.annotations.NotNull;

/**
 * Response to {@link ReconnectRequest} confirming the last sequence number sent by the server.
 *
 * @see <a href="https://lionweb.io/specification/delta/delta-api.html">LionWeb Delta API
 *     specification</a>
 */
public class ReconnectResponse extends DeltaQueryResponse {
  public final long lastSentSequenceNumber;

  public ReconnectResponse(@NotNull String queryId, long lastSentSequenceNumber) {
    super(queryId);
    this.lastSentSequenceNumber = lastSentSequenceNumber;
  }

  @Override
  public String toString() {
    return "ReconnectResponse{" + "lastSentSequenceNumber=" + lastSentSequenceNumber + '}';
  }
}
