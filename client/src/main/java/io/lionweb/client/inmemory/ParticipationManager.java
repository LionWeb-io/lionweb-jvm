package io.lionweb.client.inmemory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps track of the participations known to the {@link InMemoryServer}, together with the state
 * associated with each of them: the partitions they are subscribed to, whether they are subscribed
 * to changes to the list of partitions, and the sequence number of the last event sent to them.
 */
class ParticipationManager {
  private int nextParticipationId = 1;

  /** Participations that are currently active (signed on, not yet signed off). */
  private final Map<String, ParticipationState> activeParticipations = new ConcurrentHashMap<>();

  private static class ParticipationState {
    private final Set<String> subscribedPartitions = ConcurrentHashMap.newKeySet();
    private volatile boolean subscribedToPartitionList = false;
    private volatile long lastSentSequenceNumber = 0;
  }

  boolean isActiveParticipation(@NotNull String participationId) {
    Objects.requireNonNull(participationId, "participationId must not be null");
    return activeParticipations.containsKey(participationId);
  }

  @NotNull
  synchronized String createParticipationId() {
    String participationId = "participation-" + nextParticipationId++;
    activeParticipations.put(participationId, new ParticipationState());
    return participationId;
  }

  public void drop(@NotNull String currentParticipationId) {
    activeParticipations.remove(currentParticipationId);
  }

  /** Records that the given participation is subscribed to the contents of the given partition. */
  void subscribeToPartition(@NotNull String participationId, @NotNull String partitionId) {
    Objects.requireNonNull(partitionId, "partitionId must not be null");
    requireState(participationId).subscribedPartitions.add(partitionId);
  }

  /** Removes the subscription of the given participation to the contents of the given partition. */
  void unsubscribeFromPartition(@NotNull String participationId, @NotNull String partitionId) {
    Objects.requireNonNull(partitionId, "partitionId must not be null");
    requireState(participationId).subscribedPartitions.remove(partitionId);
  }

  /** Removes all the subscriptions to the given partition, for example because it was deleted. */
  void removeAllSubscriptionsTo(@NotNull String partitionId) {
    Objects.requireNonNull(partitionId, "partitionId must not be null");
    activeParticipations.values().forEach(s -> s.subscribedPartitions.remove(partitionId));
  }

  /** Records that the given participation is subscribed to changes to the list of partitions. */
  void subscribeToPartitionList(@NotNull String participationId) {
    requireState(participationId).subscribedToPartitionList = true;
  }

  /**
   * Returns the active participations subscribed to the contents of any of the given partitions.
   */
  @NotNull
  Set<String> participationsSubscribedToAnyOf(@NotNull Collection<String> partitionIds) {
    Set<String> result = new LinkedHashSet<>();
    activeParticipations.forEach(
        (participationId, state) -> {
          if (partitionIds.stream().anyMatch(state.subscribedPartitions::contains)) {
            result.add(participationId);
          }
        });
    return result;
  }

  /** Returns the active participations subscribed to changes to the list of partitions. */
  @NotNull
  Set<String> participationsSubscribedToPartitionList() {
    Set<String> result = new LinkedHashSet<>();
    activeParticipations.forEach(
        (participationId, state) -> {
          if (state.subscribedToPartitionList) {
            result.add(participationId);
          }
        });
    return result;
  }

  /** Records that an event with the given sequence number has been sent to the participation. */
  void recordSentEvent(@NotNull String participationId, long sequenceNumber) {
    ParticipationState state = activeParticipations.get(participationId);
    if (state != null) {
      state.lastSentSequenceNumber = sequenceNumber;
    }
  }

  /**
   * Returns the sequence number of the last event sent to the given participation, or 0 if no event
   * has been sent to it yet.
   */
  long getLastSentSequenceNumber(@NotNull String participationId) {
    return requireState(participationId).lastSentSequenceNumber;
  }

  private @NotNull ParticipationState requireState(@NotNull String participationId) {
    Objects.requireNonNull(participationId, "participationId must not be null");
    ParticipationState state = activeParticipations.get(participationId);
    if (state == null) {
      throw new IllegalArgumentException("Unknown participation: " + participationId);
    }
    return state;
  }
}
