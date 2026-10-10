package io.lionweb.client.delta;

import io.lionweb.client.delta.messages.DeltaEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Handles incoming {@link io.lionweb.client.delta.messages.DeltaEvent} notifications from a delta
 * channel.
 */
public interface DeltaEventReceiver {

  void receiveEvent(@NotNull DeltaEvent event);

  /**
   * The participation on behalf of which this receiver is receiving events, if any. Channels can
   * use it to deliver events only to the participations they are addressed to (see {@link
   * DeltaChannel#supportsTargetedEvents()}).
   *
   * @return the participation ID, or {@code null} if this receiver is not bound to a participation
   */
  default @Nullable String getParticipationId() {
    return null;
  }
}
