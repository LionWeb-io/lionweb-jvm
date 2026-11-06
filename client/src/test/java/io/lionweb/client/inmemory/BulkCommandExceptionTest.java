package io.lionweb.client.inmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class BulkCommandExceptionTest {

  @Test
  public void ofBuildsDataFromKeysAndValues() {
    BulkCommandException e =
        BulkCommandException.of(
            BulkMessageKind.NODE_IS_NOT_PARTITION, "msg", "nodeId", "n1", "parentNodeId", "p1");
    assertEquals(BulkMessageKind.NODE_IS_NOT_PARTITION, e.getKind());
    assertEquals("NodeIsNotPartition", e.getKind().getKindName());
    assertEquals("msg", e.getMessage());
    assertEquals(List.of("nodeId", "parentNodeId"), List.copyOf(e.getData().keySet()));
    assertEquals("n1", e.getData().get("nodeId"));
    assertEquals("p1", e.getData().get("parentNodeId"));
  }

  @Test
  public void ofRejectsAnOddNumberOfKeysAndValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> BulkCommandException.of(BulkMessageKind.PARENT_MISSING, "msg", "nodeId"));
  }

  @Test
  public void ofRejectsNullKeysAndValues() {
    assertThrows(
        NullPointerException.class,
        () -> BulkCommandException.of(BulkMessageKind.PARENT_MISSING, "msg", "nodeId", null));
    assertThrows(
        NullPointerException.class,
        () -> BulkCommandException.of(BulkMessageKind.PARENT_MISSING, "msg", null, "n1"));
    assertThrows(
        NullPointerException.class,
        () -> BulkCommandException.of(BulkMessageKind.PARENT_MISSING, "msg", (String[]) null));
  }

  @Test
  public void constructorRejectsNulls() {
    Map<String, String> empty = Collections.emptyMap();
    assertThrows(NullPointerException.class, () -> new BulkCommandException(null, "msg", empty));
    assertThrows(
        NullPointerException.class,
        () -> new BulkCommandException(BulkMessageKind.TREE_CYCLE, null, empty));
    assertThrows(
        NullPointerException.class,
        () -> new BulkCommandException(BulkMessageKind.TREE_CYCLE, "msg", null));
    Map<String, String> withNullValue = new HashMap<>();
    withNullValue.put("nodeId", null);
    assertThrows(
        NullPointerException.class,
        () -> new BulkCommandException(BulkMessageKind.TREE_CYCLE, "msg", withNullValue));
  }

  @Test
  public void dataCannotBeModified() {
    BulkCommandException e =
        BulkCommandException.of(BulkMessageKind.TREE_CYCLE, "msg", "nodeId", "n1");
    assertThrows(UnsupportedOperationException.class, () -> e.getData().put("x", "y"));
  }

  @Test
  public void retrieveResultRejectsNulls() {
    assertThrows(NullPointerException.class, () -> new RetrieveResult(null, List.of()));
    assertThrows(NullPointerException.class, () -> new RetrieveResult(List.of(), null));
  }
}
