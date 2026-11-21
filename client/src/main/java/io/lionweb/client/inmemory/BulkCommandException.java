package io.lionweb.client.inmemory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * A bulk command that has been rejected. It carries the message kind and the data defined by the
 * LionWeb bulk API specification (for example {@code PartitionAlreadyExists} with {@code nodeId}),
 * so that servers can report it as specified.
 *
 * <p>It is an {@link IllegalArgumentException}, as the in-memory server used to report invalid
 * requests that way. A command failing with this exception leaves the repository unchanged.
 */
public class BulkCommandException extends IllegalArgumentException {
  private final @NotNull BulkMessageKind kind;
  private final @NotNull Map<String, String> data;

  public BulkCommandException(
      @NotNull BulkMessageKind kind, @NotNull String message, @NotNull Map<String, String> data) {
    super(Objects.requireNonNull(message, "message should not be null"));
    this.kind = Objects.requireNonNull(kind, "kind should not be null");
    Objects.requireNonNull(data, "data should not be null");
    Map<String, String> copy = new LinkedHashMap<>(data.size());
    for (Map.Entry<String, String> entry : data.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "data keys should not be null");
      Objects.requireNonNull(
          entry.getValue(), "the value of data key " + entry.getKey() + " should not be null");
      copy.put(entry.getKey(), entry.getValue());
    }
    this.data = Collections.unmodifiableMap(copy);
  }

  /** The message kind, e.g. {@link BulkMessageKind#PARENT_MISSING}. */
  public @NotNull BulkMessageKind getKind() {
    return kind;
  }

  /** The data of the message: keys and values are strings. */
  public @NotNull Map<String, String> getData() {
    return data;
  }

  /**
   * Creates an exception whose data is given as alternating keys and values, e.g. {@code "nodeId",
   * "n1"}.
   *
   * @throws IllegalArgumentException if an odd number of keys and values is given
   */
  static BulkCommandException of(
      @NotNull BulkMessageKind kind, @NotNull String message, @NotNull String... keyValues) {
    Objects.requireNonNull(keyValues, "keyValues should not be null");
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException(
          "keyValues should contain pairs of keys and values, but it has "
              + keyValues.length
              + " elements");
    }
    Map<String, String> data = new LinkedHashMap<>(keyValues.length / 2);
    for (int i = 0; i < keyValues.length; i += 2) {
      data.put(keyValues[i], keyValues[i + 1]);
    }
    return new BulkCommandException(kind, message, data);
  }
}
