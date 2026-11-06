package io.lionweb.client.inmemory;

import org.jetbrains.annotations.NotNull;

/**
 * The kinds of the messages the in-memory repository reports for bulk commands.
 *
 * <p>Where the LionWeb bulk API specification defines a kind, its name is used. Where the
 * specification leaves the kind to be defined, a descriptive name is used.
 */
public enum BulkMessageKind {
  DEPTH_LIMIT_INCORRECT("DepthLimitIncorrect"),
  DUPLICATE_NODE_ID("DuplicateNodeId"),
  EMPTY_ID_LIST("EmptyIdList"),
  ID_NOT_FOUND("IdNotFound"),
  IDS_INCORRECT("IdsIncorrect"),
  NODE_ALREADY_EXISTS("NodeAlreadyExists"),
  NODE_HAS_MULTIPLE_PARENTS("NodeHasMultipleParents"),
  NODE_IS_NOT_PARTITION("NodeIsNotPartition"),
  NODE_LISTED_MORE_THAN_ONCE("NodeListedMoreThanOnce"),
  NULL_CHUNK("NullChunk"),
  PARENT_MISMATCH("ParentMismatch"),
  PARENT_MISSING("ParentMissing"),
  PARTITION_ALREADY_EXISTS("PartitionAlreadyExists"),
  PARTITION_HAS_PARENT("PartitionHasParent"),
  TREE_CYCLE("TreeCycle");

  private final @NotNull String kindName;

  BulkMessageKind(@NotNull String kindName) {
    this.kindName = kindName;
  }

  /** The name of the kind, as it appears in the messages, e.g. {@code ParentMissing}. */
  public @NotNull String getKindName() {
    return kindName;
  }

  @Override
  public String toString() {
    return kindName;
  }
}
