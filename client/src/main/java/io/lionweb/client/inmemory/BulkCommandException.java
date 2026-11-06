package io.lionweb.client.inmemory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
  private final @NotNull String kind;
  private final @NotNull Map<String, String> data;

  public BulkCommandException(
      @NotNull String kind, @NotNull String message, @NotNull Map<String, String> data) {
    super(message);
    this.kind = kind;
    this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }

  /** The message kind, e.g. {@code ParentMissing}. */
  public @NotNull String getKind() {
    return kind;
  }

  /** The data of the message: keys and values are strings. */
  public @NotNull Map<String, String> getData() {
    return data;
  }

  static BulkCommandException of(String kind, String message, String... keyValues) {
    Map<String, String> data = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      data.put(keyValues[i], keyValues[i + 1]);
    }
    return new BulkCommandException(kind, message, data);
  }
}
