package io.lionweb.client.delta.messages.queries.participations;

import io.lionweb.client.delta.messages.DeltaQuery;
import org.jetbrains.annotations.NotNull;

/**
 * Query sent by a client to end its current participation in the delta protocol.
 *
 * @see <a href="https://lionweb.io/specification/delta/delta-api.html">LionWeb Delta API
 *     specification</a>
 */
public class SignOffRequest extends DeltaQuery {

  public SignOffRequest(@NotNull String queryId) {
    super(queryId);
  }

  @Override
  public String toString() {
    return "SignOffRequest{" + "queryId='" + queryId + '\'' + '}';
  }
}
