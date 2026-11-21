package io.lionweb.client.inmemory;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.LionWebVersion;
import io.lionweb.client.api.ClassifierKey;
import io.lionweb.client.api.HistorySupport;
import io.lionweb.client.api.RepositoryConfiguration;
import io.lionweb.client.api.RepositoryVersionToken;
import io.lionweb.serialization.LowLevelJsonSerialization;
import io.lionweb.serialization.data.MetaPointer;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The commands of the bulk API are transactional and enforce the tree invariants (see
 * https://lionweb.io/specification/2024.1/bulk/repo-access-api.html#validity). For each command
 * failing in the middle we verify that nothing has changed, comparing the whole state.
 *
 * <p>The initial content of the repository is:
 *
 * <pre>
 * p1
 *  +- c1 (containment "cont")
 *  |   +- g1
 *  |   +- annotation a1
 *  +- c2
 * p2
 *  +- d1
 * </pre>
 */
public class TransactionalBulkCommandsTest {

  private static final String REPO = "repo";
  private static final MetaPointer CONCEPT = MetaPointer.get("lang", "1", "Concept");
  private static final MetaPointer OTHER_CONCEPT = MetaPointer.get("lang", "1", "Other");
  private static final MetaPointer CONT = MetaPointer.get("lang", "1", "cont");

  private InMemoryServer server;

  @BeforeEach
  public void setup() {
    server = new InMemoryServer();
    server.createRepository(
        new RepositoryConfiguration(REPO, LionWebVersion.v2024_1, HistorySupport.DISABLED));
    SerializedClassifierInstance p1 = node("p1", null, "c1", "c2");
    SerializedClassifierInstance c1 = node("c1", "p1", "g1");
    c1.addAnnotation("a1");
    SerializedClassifierInstance p2 = node("p2", null, "d1");
    server.createPartitionFromChunk(
        REPO,
        Arrays.asList(
            p1, c1, node("c2", "p1"), node("g1", "c1"), node("a1", "c1"), p2, node("d1", "p2")));
    assertTrue(server.checkConsistency().isSuccessful());
  }

  private static SerializedClassifierInstance node(String id, String parent, String... children) {
    SerializedClassifierInstance n = new SerializedClassifierInstance(id, CONCEPT);
    n.setParentNodeID(parent);
    if (children.length > 0) {
      n.unsafeAppendContainmentValue(CONT, Arrays.asList(children));
    }
    return n;
  }

  /** Everything the repository holds, in a form which can be compared. */
  private String snapshot() {
    RepositoryData data = server.getRepository(REPO);
    List<SerializedClassifierInstance> nodes =
        data.nodesByID.values().stream()
            .sorted(Comparator.comparing(SerializedClassifierInstance::getID))
            .collect(Collectors.toList());
    String nodesJson =
        new LowLevelJsonSerialization()
            .serializeToJsonString(
                LowLevelJsonSerialization.groupNodesIntoSerializationBlock(
                    nodes, LionWebVersion.v2024_1));
    return nodesJson
        + "\npartitions="
        + data.partitionIDs
        + "\nbyClassifier="
        + new TreeMap<>(
            server.nodesByClassifier(REPO).entrySet().stream()
                .collect(
                    Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> new TreeSet<>(e.getValue().getIds()) + "/" + e.getValue().getSize())))
        + "\nlistedPartitions="
        + server.listPartitionIDs(REPO);
  }

  private BulkCommandException assertRejectedAndUnchanged(
      BulkMessageKind expectedKind, Runnable command, String... expectedData) {
    String before = snapshot();
    BulkCommandException e = assertThrows(BulkCommandException.class, command::run);
    assertEquals(expectedKind, e.getKind(), e.getMessage());
    for (int i = 0; i + 1 < expectedData.length; i += 2) {
      assertEquals(expectedData[i + 1], e.getData().get(expectedData[i]), e.getMessage());
    }
    assertEquals(before, snapshot());
    assertTrue(server.checkConsistency().isSuccessful());
    return e;
  }

  private void store(SerializedClassifierInstance... nodes) {
    server.store(REPO, Arrays.asList(nodes));
  }

  //
  // store
  //

  @Test
  public void storeWithUnknownChildIsRejectedAndNothingChanges() {
    // The first two nodes are fine (a new child of c2), the last one lists an unknown child
    SerializedClassifierInstance c2 = node("c2", "p1", "newChild");
    SerializedClassifierInstance newChild = node("newChild", "c2");
    SerializedClassifierInstance d1 = node("d1", "p2", "unknown");
    assertRejectedAndUnchanged(
        BulkMessageKind.PARENT_MISSING, () -> store(c2, newChild, d1), "nodeId", "unknown");
  }

  @Test
  public void storeWithUnknownParentIsRejectedAndNothingChanges() {
    SerializedClassifierInstance orphan = node("orphan", "nowhere");
    assertRejectedAndUnchanged(
        BulkMessageKind.PARENT_MISSING,
        () -> store(node("c2", "p1", "n"), node("n", "c2"), orphan),
        "nodeId",
        "orphan");
  }

  @Test
  public void storeOfNonPartitionWithoutParentIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(
        BulkMessageKind.PARENT_MISSING,
        () -> store(node("c2", "p1", "n"), node("n", "c2"), node("lost", null)),
        "nodeId",
        "lost");
  }

  @Test
  public void storeOfNodeContainedInTwoParentsIsRejectedAndNothingChanges() {
    // The new node is listed by both c2 and d1
    assertRejectedAndUnchanged(
        BulkMessageKind.NODE_HAS_MULTIPLE_PARENTS,
        () -> store(node("c2", "p1", "n"), node("n", "c2"), node("d1", "p2", "n")),
        "nodeId",
        "n");
  }

  @Test
  public void storeOfExistingNodeListedByTwoParentsIsRejectedAndNothingChanges() {
    // g1 is moved to both c2 and d1, while c1 is not sent
    assertRejectedAndUnchanged(
        BulkMessageKind.NODE_HAS_MULTIPLE_PARENTS,
        () -> store(node("c2", "p1", "g1"), node("d1", "p2", "g1")),
        "nodeId",
        "g1");
  }

  @Test
  public void storeOfNodeListedTwiceByTheSameParentIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(
        BulkMessageKind.NODE_LISTED_MORE_THAN_ONCE,
        () -> store(node("c2", "p1", "n", "n"), node("n", "c2")),
        "nodeId",
        "n");
  }

  @Test
  public void storeOfNodeBothChildAndAnnotationIsRejectedAndNothingChanges() {
    SerializedClassifierInstance c2 = node("c2", "p1", "n");
    c2.addAnnotation("n");
    assertRejectedAndUnchanged(
        BulkMessageKind.NODE_LISTED_MORE_THAN_ONCE, () -> store(c2, node("n", "c2")));
  }

  @Test
  public void storeOfNodeSentTwiceIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(
        BulkMessageKind.DUPLICATE_NODE_ID,
        () -> store(node("c2", "p1", "n"), node("n", "c2"), node("n", "c2")),
        "nodeId",
        "n");
  }

  @Test
  public void storeMovingNodeUnderItsDescendantIsRejectedAndNothingChanges() {
    // c1 would be contained in its own child g1
    assertRejectedAndUnchanged(
        BulkMessageKind.TREE_CYCLE, () -> store(node("g1", "c1", "c1"), node("c1", "g1", "g1")));
  }

  @Test
  public void storeMovingNodeUnderItsDescendantSendingOnlyTheNewParentIsRejected() {
    assertRejectedAndUnchanged(BulkMessageKind.TREE_CYCLE, () -> store(node("g1", "c1", "c1")));
  }

  @Test
  public void storeOfNodeContainingItselfIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(
        BulkMessageKind.TREE_CYCLE, () -> store(node("c2", "p1", "c2")), "nodeId", "c2");
  }

  @Test
  public void storeOfDetachedLoopIsRejectedAndNothingChanges() {
    // x and y contain each other, and are not connected to any partition
    assertRejectedAndUnchanged(
        BulkMessageKind.TREE_CYCLE, () -> store(node("x", "y", "y"), node("y", "x", "x")));
  }

  @Test
  public void storeWhereParentDoesNotListTheChildIsRejectedAndNothingChanges() {
    // n declares c2 as parent, but c2 (sent too) does not contain it
    assertRejectedAndUnchanged(
        BulkMessageKind.PARENT_MISMATCH,
        () -> store(node("c2", "p1"), node("n", "c2")),
        "nodeId",
        "n",
        "parentNodeId",
        "c2");
  }

  @Test
  public void storeWhereStoredParentDoesNotListTheChildIsRejectedAndNothingChanges() {
    // c2 is not sent, and it does not contain n
    assertRejectedAndUnchanged(
        BulkMessageKind.PARENT_MISMATCH,
        () -> store(node("d1", "p2", "ok"), node("ok", "d1"), node("n", "c2")));
  }

  @Test
  public void storeWhereChildDeclaresDifferentParentIsRejectedAndNothingChanges() {
    // c2 lists g1 as child, but g1 says that its parent is d1
    assertRejectedAndUnchanged(
        BulkMessageKind.PARENT_MISMATCH, () -> store(node("c2", "p1", "g1"), node("g1", "d1")));
  }

  @Test
  public void storeOfPartitionWithParentIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(
        BulkMessageKind.PARTITION_HAS_PARENT,
        () -> store(node("c2", "p1", "n"), node("n", "c2"), node("p2", "c2", "d1")),
        "nodeId",
        "p2");
  }

  @Test
  public void storeMovingPartitionUnderANodeIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(
        BulkMessageKind.PARTITION_HAS_PARENT,
        () -> store(node("c2", "p1", "p2")),
        "nodeId",
        "p2",
        "parentNodeId",
        "c2");
  }

  @Test
  public void storeOfNullChunkIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(BulkMessageKind.NULL_CHUNK, () -> server.store(REPO, null));
  }

  @Test
  public void failedCommandDoesNotConsumeAVersion() {
    RepositoryVersionToken v1 = server.store(REPO, List.of(node("d1", "p2")));
    assertThrows(BulkCommandException.class, () -> store(node("x", "y", "y"), node("y", "x", "x")));
    RepositoryVersionToken v2 = server.store(REPO, List.of(node("d1", "p2")));
    assertEquals("v-" + (Integer.parseInt(v1.getToken().substring(2)) + 1), v2.getToken());
  }

  @Test
  public void storeMovingChildSendingOnlyTheNewParentRemovesItFromTheOldParent() {
    // g1 moves from c1 to c2, and c1 is not sent
    store(node("c2", "p1", "g1"));
    RepositoryData data = server.getRepository(REPO);
    assertEquals("c2", data.nodesByID.get("g1").getParentNodeID());
    assertEquals(Collections.emptyList(), data.nodesByID.get("c1").getChildren());
    assertEquals(List.of("g1"), data.nodesByID.get("c2").getChildren());
    assertTrue(server.checkConsistency().isSuccessful(), server.checkConsistency().toString());
  }

  @Test
  public void storeDroppingAChildDeletesItWithItsDescendants() {
    store(node("p1", null, "c2"));
    RepositoryData data = server.getRepository(REPO);
    assertEquals(
        new TreeSet<>(List.of("p1", "c2", "p2", "d1")), new TreeSet<>(data.nodesByID.keySet()));
    assertTrue(server.checkConsistency().isSuccessful(), server.checkConsistency().toString());
    assertEquals(
        4, server.nodesByClassifier(REPO).values().stream().mapToInt(r -> r.getSize()).sum());
  }

  @Test
  public void storeUpdatingClassifierKeepsIndexConsistent() {
    SerializedClassifierInstance changed = new SerializedClassifierInstance("d1", OTHER_CONCEPT);
    changed.setParentNodeID("p2");
    store(changed);
    assertEquals(1, server.nodesByClassifier(REPO, new ClassifierKey("lang", "Other")).getSize());
    assertEquals(6, server.nodesByClassifier(REPO, new ClassifierKey("lang", "Concept")).getSize());
  }

  //
  // createPartitions
  //

  @Test
  public void createPartitionsWithExistingPartitionIsRejectedAndNothingChanges() {
    // p3 is new, p2 exists
    assertRejectedAndUnchanged(
        BulkMessageKind.PARTITION_ALREADY_EXISTS,
        () ->
            server.createPartitionFromChunk(
                REPO, Arrays.asList(node("p3", null), node("p2", null))),
        "nodeId",
        "p2");
  }

  @Test
  public void createPartitionsWithExistingNonPartitionNodeIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(
        BulkMessageKind.PARTITION_ALREADY_EXISTS,
        () ->
            server.createPartitionFromChunk(
                REPO, Arrays.asList(node("p3", null), node("c1", null))),
        "nodeId",
        "c1");
  }

  @Test
  public void createPartitionsWithParentIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(
        BulkMessageKind.PARTITION_HAS_PARENT,
        () ->
            server.createPartitionFromChunk(
                REPO, Arrays.asList(node("p3", null), node("p4", "c1"))),
        "nodeId",
        "p4");
  }

  @Test
  public void createPartitionsRepeatedTwiceInTheSameRequestIsRejectedAndNothingChanges() {
    assertRejectedAndUnchanged(
        BulkMessageKind.DUPLICATE_NODE_ID,
        () ->
            server.createPartitionFromChunk(
                REPO, Arrays.asList(node("p3", null), node("p3", null))));
  }

  @Test
  public void createPartitionsWithInvalidTreeIsRejectedAndNothingChanges() {
    // p3 is fine, p4 contains an unknown node
    assertRejectedAndUnchanged(
        BulkMessageKind.PARENT_MISSING,
        () ->
            server.createPartitionFromChunk(
                REPO, Arrays.asList(node("p3", null), node("p4", null, "unknown"))),
        "nodeId",
        "unknown");
  }

  @Test
  public void createPartitionsWithNodeMovedFromExistingTreeIsRejectedAndNothingChanges() {
    // g1 exists already: creating partitions cannot move nodes
    assertRejectedAndUnchanged(
        BulkMessageKind.NODE_ALREADY_EXISTS,
        () ->
            server.createPartitionFromChunk(
                REPO, Arrays.asList(node("p3", null, "n"), node("n", "p3", "g1"))),
        "nodeId",
        "g1");
  }

  @Test
  public void createPartitionsAcceptsNewPartitionsWithTheirTrees() {
    server.createPartitionFromChunk(
        REPO, Arrays.asList(node("p3", null, "n"), node("n", "p3"), node("p4", null)));
    assertEquals(List.of("p1", "p2", "p3", "p4"), server.listPartitionIDs(REPO));
    assertTrue(server.checkConsistency().isSuccessful());
  }

  @Test
  public void createPartitionsWithEmptyChunkSucceeds() {
    String before = snapshot();
    server.createPartitionFromChunk(REPO, Collections.emptyList());
    assertEquals(before, snapshot());
  }

  //
  // deletePartitions
  //

  @Test
  public void deletePartitionsWithNonPartitionIsRejectedAndNothingChanges() {
    // p1 is a valid partition and comes first, c1 is not a partition
    assertRejectedAndUnchanged(
        BulkMessageKind.NODE_IS_NOT_PARTITION,
        () -> server.deletePartitions(REPO, Arrays.asList("p1", "c1")),
        "nodeId",
        "c1",
        "parentNodeId",
        "p1");
  }

  @Test
  public void deletePartitionsDeletesChildrenAndAnnotationsTransitively() {
    server.deletePartitions(REPO, List.of("p1"));
    RepositoryData data = server.getRepository(REPO);
    assertEquals(new TreeSet<>(List.of("p2", "d1")), new TreeSet<>(data.nodesByID.keySet()));
    assertEquals(List.of("p2"), server.listPartitionIDs(REPO));
    assertEquals(
        2, server.nodesByClassifier(REPO).values().stream().mapToInt(r -> r.getSize()).sum());
    assertTrue(server.checkConsistency().isSuccessful());
  }

  @Test
  public void deletePartitionsIgnoresUnknownIds() {
    server.deletePartitions(REPO, Arrays.asList("unknown", "p2", "p2"));
    assertEquals(List.of("p1"), server.listPartitionIDs(REPO));
    assertFalse(server.getRepository(REPO).nodesByID.containsKey("d1"));
  }

  //
  // retrieve
  //

  @Test
  public void retrieveReportsUnknownIdsWithoutFailing() {
    String before = snapshot();
    RetrieveResult result =
        server.retrieveReportingUnknown(REPO, Arrays.asList("c2", "unknown", "d1", "other"), 0);
    assertEquals(
        List.of("c2", "d1"),
        result.getNodes().stream()
            .map(SerializedClassifierInstance::getID)
            .collect(Collectors.toList()));
    assertEquals(List.of("unknown", "other"), result.getUnknownIds());
    assertEquals(before, snapshot());
  }

  @Test
  public void retrieveFailsOnUnknownIdWhenNotReportingThem() {
    BulkCommandException e =
        assertThrows(
            BulkCommandException.class, () -> server.retrieve(REPO, List.of("c2", "unknown"), 0));
    assertEquals(BulkMessageKind.ID_NOT_FOUND, e.getKind());
    assertEquals("unknown", e.getData().get("nodeId"));
  }

  @Test
  public void retrieveWithNegativeDepthLimitIsRejected() {
    BulkCommandException e =
        assertThrows(BulkCommandException.class, () -> server.retrieve(REPO, List.of("p1"), -1));
    assertEquals(BulkMessageKind.DEPTH_LIMIT_INCORRECT, e.getKind());
    assertEquals("-1", e.getData().get("depthLimit"));
  }

  @Test
  public void retrieveRespectsDepthLimit() {
    assertEquals(1, server.retrieve(REPO, List.of("p1"), 0).size());
    assertEquals(3, server.retrieve(REPO, List.of("p1"), 1).size());
    assertEquals(5, server.retrieve(REPO, List.of("p1"), 2).size());
  }

  //
  // Concurrency
  //

  @Test
  public void rejectedCommandsAreNeverVisibleToConcurrentReaders() throws Exception {
    AtomicBoolean stop = new AtomicBoolean(false);
    AtomicBoolean leaked = new AtomicBoolean(false);
    Thread reader =
        new Thread(
            () -> {
              while (!stop.get()) {
                Set<String> ids =
                    server.retrieve(REPO, List.of("p1"), Integer.MAX_VALUE).stream()
                        .map(SerializedClassifierInstance::getID)
                        .collect(Collectors.toSet());
                if (ids.contains("partial") || !ids.contains("g1")) {
                  leaked.set(true);
                }
              }
            });
    reader.start();
    for (int i = 0; i < 2000; i++) {
      // Adding "partial" under c2 is fine, but the request is invalid as a whole
      assertThrows(
          BulkCommandException.class,
          () ->
              store(node("c2", "p1", "partial"), node("partial", "c2"), node("d1", "p2", "nope")));
    }
    stop.set(true);
    reader.join();
    assertFalse(leaked.get());
  }
}
