package io.lionweb.client.delta.messages.queries;

import io.lionweb.client.delta.messages.DeltaQueryResponse;
import io.lionweb.client.delta.messages.events.StandardErrorCode;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Error response indicating a query or operation has failed. */
public class ErrorResponse extends DeltaQueryResponse {
  /** Machine-readable error code identifying the type of error. */
  public String errorCode;

  /** Human-readable description of the error. */
  public String message;

  public ErrorResponse(@NotNull String queryId) {
    super(queryId);
  }

  public ErrorResponse(
      @NotNull String queryId, @NotNull String errorCode, @Nullable String message) {
    super(queryId);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode should not be null");
    this.message = message;
  }

  public ErrorResponse(
      @NotNull String queryId,
      @NotNull StandardErrorCode standardErrorCode,
      @Nullable String message) {
    this(
        queryId,
        Objects.requireNonNull(standardErrorCode, "standardErrorCode should not be null").code,
        message);
  }

  @Override
  public String toString() {
    return "ErrorResponse{"
        + "errorCode='"
        + errorCode
        + '\''
        + ", message='"
        + message
        + '\''
        + ", queryId='"
        + queryId
        + '\''
        + '}';
  }
}
