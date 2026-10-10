package io.lionweb.client.delta;

import io.lionweb.client.delta.messages.*;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The DeltaChannel must be a specific link between a Client and the Server. Different clients
 * should use different DeltaChannels because the clientId must be determined from the channel.
 */
public interface DeltaChannel {
  /**
   * Queries initiated/requested by the client, with synchronous response by the repository. A query
   * requests some information from the repository without changing the repository’s contents. The
   * repository gathers all information needed to answer the query, and sends the information back.
   * The repository might reply invalid queries with a failure message. We also use queries for
   * managing participations.
   */
  @Nullable
  DeltaQueryResponse sendQuery(@NotNull Function<String, DeltaQuery> queryProducer);

  /**
   * Commands initiated/requested by the client, with synchronous response by the repository. A
   * command requests some change to the repository. The repository quickly confirms having received
   * the command, or rejects a failed command.[5] However, the repository processes the command
   * asynchronously, and eventually broadcasts the effect(s) as event.
   */
  void sendCommand(
      @NotNull String participationId, @NotNull Function<String, DeltaCommand> commandProducer);

  void sendEvent(@NotNull Function<Integer, DeltaEvent> eventProducer);

  /**
   * Whether this channel supports delivering events to specific participations, through {@link
   * #sendEvent(String, Function)}. Channels not supporting it receive all the events through {@link
   * #sendEvent(Function)}, and are responsible for routing them to the interested participations.
   */
  default boolean supportsTargetedEvents() {
    return false;
  }

  /**
   * Events sent by the repository to a specific participation, for example because it is subscribed
   * to the partition affected by the event. The event producer is invoked once for each delivery
   * and receives the sequence number assigned to it. Only used if {@link #supportsTargetedEvents()}
   * returns true.
   */
  default void sendEvent(
      @NotNull String participationId, @NotNull Function<Integer, DeltaEvent> eventProducer) {
    throw new UnsupportedOperationException("This channel does not support targeted events");
  }

  void registerEventReceiver(@NotNull DeltaEventReceiver deltaEventReceiver);

  void unregisterEventReceiver(@NotNull DeltaEventReceiver deltaEventReceiver);

  void registerCommandReceiver(@NotNull DeltaCommandReceiver deltaCommandReceiver);

  void unregisterCommandReceiver(@NotNull DeltaCommandReceiver deltaCommandReceiver);

  void registerQueryReceiver(@NotNull DeltaQueryReceiver deltaQueryReceiver);

  void unregisterQueryReceiver(@NotNull DeltaQueryReceiver deltaQueryReceiver);

  void registerQueryResponseReceiver(
      @NotNull DeltaQueryResponseReceiver deltaQueryResponseReceiver);

  void unregisterQueryResponseReceiver(
      @NotNull DeltaQueryResponseReceiver deltaQueryResponseReceiver);
}
