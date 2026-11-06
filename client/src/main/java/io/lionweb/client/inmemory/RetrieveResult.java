package io.lionweb.client.inmemory;

import io.lionweb.serialization.data.SerializedClassifierInstance;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The outcome of a successful retrieve: the nodes found, and the requested ids which do not exist
 * in the repository (the bulk API reports them as {@code IdNotFound} messages, without failing).
 */
public class RetrieveResult {
  private final @NotNull List<SerializedClassifierInstance> nodes;
  private final @NotNull List<String> unknownIds;

  public RetrieveResult(
      @NotNull List<SerializedClassifierInstance> nodes, @NotNull List<String> unknownIds) {
    this.nodes = nodes;
    this.unknownIds = unknownIds;
  }

  public @NotNull List<SerializedClassifierInstance> getNodes() {
    return nodes;
  }

  public @NotNull List<String> getUnknownIds() {
    return unknownIds;
  }
}
