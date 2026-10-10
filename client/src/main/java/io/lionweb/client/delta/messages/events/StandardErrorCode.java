package io.lionweb.client.delta.messages.events;

import org.jetbrains.annotations.NotNull;

/**
 * Well-known error codes that may appear in {@link ErrorEvent} messages.
 *
 * @see <a href="https://lionweb.io/specification/delta/delta-api.html">LionWeb Delta API
 *     specification</a>
 */
public enum StandardErrorCode {
  INVALID_PARTICIPATION("invalidParticipation"),
  MESSAGE_KIND_UNKNOWN("messageKindUnknown"),
  NODE_ALREADY_EXISTS("nodeAlreadyExists"),
  UNKNOWN_NODE("unknownNode"),
  UNKNOWN_INDEX("unknownIndex"),
  INDEX_NODE_MISMATCH("indexNodeMismatch"),
  MOVE_WITHOUT_PARENT("moveWithoutParent"),
  INVALID_MOVE("invalidMove"),
  UNDEFINED_REFERENCE_TARGET("undefinedReferenceTarget");

  public final @NotNull String code;

  StandardErrorCode(@NotNull String code) {
    this.code = code;
  }
}
