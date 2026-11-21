package io.lionweb.client.inmemory;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * The kinds of the messages the in-memory repository reports for bulk commands.
 *
 * <p>Where the LionWeb bulk API specification defines a kind, its name is used. Where the
 * specification leaves the kind to be defined, a descriptive name is used.
 */
public enum BulkMessageKind {
  DEPTH_LIMIT_INCORRECT,
  DUPLICATE_NODE_ID,
  EMPTY_ID_LIST,
  ID_NOT_FOUND,
  IDS_INCORRECT,
  NODE_ALREADY_EXISTS,
  NODE_HAS_MULTIPLE_PARENTS,
  NODE_IS_NOT_PARTITION,
  NODE_LISTED_MORE_THAN_ONCE,
  NULL_CHUNK,
  PARENT_MISMATCH,
  PARENT_MISSING,
  PARTITION_ALREADY_EXISTS,
  PARTITION_HAS_PARENT,
  TREE_CYCLE;

  private final @NotNull String kindName;

  BulkMessageKind() {
    this.kindName = toPascalCase(name());
  }

  /**
   * The name of the kind, as it appears in the messages: the name of the constant in PascalCase,
   * e.g. {@code ParentMissing} for {@link #PARENT_MISSING}.
   */
  public @NotNull String getKindName() {
    return kindName;
  }

  @Override
  public String toString() {
    return kindName;
  }

  private static String toPascalCase(String constantName) {
    StringBuilder sb = new StringBuilder(constantName.length());
    for (String word : constantName.split("_")) {
      sb.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
    }
    return sb.toString();
  }
}
