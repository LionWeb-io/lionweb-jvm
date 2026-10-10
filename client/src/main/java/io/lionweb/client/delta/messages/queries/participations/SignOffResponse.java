package io.lionweb.client.delta.messages.queries.participations;

import io.lionweb.client.delta.messages.DeltaQueryResponse;
import org.jetbrains.annotations.NotNull;

/**
 * Response acknowledging a {@link SignOffRequest}.
 *
 * @see <a href="https://lionweb.io/specification/delta/delta-api.html">LionWeb Delta API
 *     specification</a>
 */
public class SignOffResponse extends DeltaQueryResponse {

  public SignOffResponse(@NotNull String queryId) {
    super(queryId);
  }

  @Override
  public String toString() {
    return "SignOffResponse{" + "queryId='" + queryId + '\'' + '}';
  }
}
