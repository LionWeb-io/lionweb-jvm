package io.lionweb.client.delta;

import io.lionweb.client.delta.messages.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An in-process {@link DeltaChannel} that routes messages directly between registered receivers
 * without any network or serialization overhead. Useful for testing and single-JVM deployments.
 */
public class InMemoryDeltaChannel implements DeltaChannel {
  private final @NotNull Set<DeltaEventReceiver> eventReceivers = new HashSet<>();
  private @Nullable DeltaCommandReceiver commandReceiver;
  private @Nullable DeltaQueryReceiver queryReceiver;
  private final @NotNull List<DeltaQueryResponseReceiver> queryResponseReceivers =
      new ArrayList<>();
  private int nextEventId = 1;
  private int nextCommandId = 1;
  private int nextQueryId = 1;

  @Nullable
  @Override
  public DeltaQueryResponse sendQuery(@NotNull Function<String, DeltaQuery> queryProducer) {
    if (queryReceiver != null) {
      DeltaQueryResponse response =
          queryReceiver.receiveQuery(queryProducer.apply("query-" + nextQueryId++));
      queryResponseReceivers.forEach(receiver -> receiver.receiveQueryResponse(response));
      return response;
    }

    return null;
  }

  @Override
  public void sendCommand(
      @NotNull String participationId, @NotNull Function<String, DeltaCommand> commandProducer) {
    Objects.requireNonNull(participationId, "participationId must not be null");
    if (commandReceiver != null) {
      commandReceiver.receiveCommand(
          participationId, commandProducer.apply("cmd-" + nextCommandId++));
    }
  }

  @Override
  public void registerEventReceiver(@NotNull DeltaEventReceiver deltaEventReceiver) {
    eventReceivers.add(deltaEventReceiver);
  }

  @Override
  public void unregisterEventReceiver(@NotNull DeltaEventReceiver deltaEventReceiver) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public void registerCommandReceiver(@NotNull DeltaCommandReceiver commandReceiver) {
    this.commandReceiver = commandReceiver;
  }

  @Override
  public void unregisterCommandReceiver(@NotNull DeltaCommandReceiver deltaCommandReceiver) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public void sendEvent(@NotNull Function<Integer, DeltaEvent> eventProducer) {
    eventReceivers.forEach(receiver -> receiver.receiveEvent(eventProducer.apply(nextEventId++)));
  }

  @Override
  public boolean supportsTargetedEvents() {
    return true;
  }

  /** Delivers the event only to the receivers bound to the given participation. */
  @Override
  public void sendEvent(
      @NotNull String participationId, @NotNull Function<Integer, DeltaEvent> eventProducer) {
    Objects.requireNonNull(participationId, "participationId must not be null");
    Objects.requireNonNull(eventProducer, "eventProducer must not be null");
    eventReceivers.stream()
        .filter(receiver -> participationId.equals(receiver.getParticipationId()))
        .collect(Collectors.toList())
        .forEach(receiver -> receiver.receiveEvent(eventProducer.apply(nextEventId++)));
  }

  @Override
  public void registerQueryReceiver(@NotNull DeltaQueryReceiver deltaQueryReceiver) {
    this.queryReceiver = deltaQueryReceiver;
  }

  @Override
  public void unregisterQueryReceiver(@NotNull DeltaQueryReceiver deltaQueryReceiver) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public void registerQueryResponseReceiver(
      @NotNull DeltaQueryResponseReceiver deltaQueryResponseReceiver) {
    this.queryResponseReceivers.add(deltaQueryResponseReceiver);
  }

  @Override
  public void unregisterQueryResponseReceiver(
      @NotNull DeltaQueryResponseReceiver deltaQueryResponseReceiver) {
    throw new UnsupportedOperationException("Not supported yet.");
  }
}
