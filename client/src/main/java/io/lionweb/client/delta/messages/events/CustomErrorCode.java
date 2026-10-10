package io.lionweb.client.delta.messages.events;

/**
 * Non-standard error codes used by this implementation in {@link ErrorEvent} and {@link
 * io.lionweb.client.delta.messages.queries.ErrorResponse} messages.
 *
 * <p>The Delta specification allows custom errors, as long as their technical name starts with
 * {@code Custom_}. For the error codes defined by the specification, see {@link StandardErrorCode}.
 *
 * @see <a href="https://lionweb.io/specification/delta/delta-api.html">LionWeb Delta API
 *     specification</a>
 */
public final class CustomErrorCode {

  /**
   * A valid, recognized Delta message is not supported by this implementation.
   *
   * <p>This differs from {@link StandardErrorCode#MESSAGE_KIND_UNKNOWN}, which signals that the
   * message itself is not recognized.
   */
  public static final String NOT_IMPLEMENTED = "Custom_notImplemented";

  private CustomErrorCode() {}
}
