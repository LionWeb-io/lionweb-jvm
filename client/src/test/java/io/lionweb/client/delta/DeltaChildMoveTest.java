package io.lionweb.client.delta;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.client.inmemory.InMemoryServer;
import io.lionweb.language.Concept;
import io.lionweb.language.Language;
import io.lionweb.serialization.JsonSerialization;
import io.lionweb.serialization.data.MetaPointer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for Delta protocol implementation: child-move operations.
 *
 * <p>Covers: MoveChildInSameContainment, MoveChildFromOtherContainment,
 * MoveChildFromOtherContainmentInSameParent, and ReplaceChild.
 */
public class DeltaChildMoveTest extends AbstractDeltaProtocolTest {

  /**
   * A client can reorder children within a containment using MoveChildInSameContainment. Both
   * clients see the reordered list after the event is processed.
   */
  @Test
  public void moveChildInSameContainment() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang1 = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang1, ser);

    Language lang2 = (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", ser);
    Assertions.assertNotNull(lang2);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();
    client1.monitorPartition(lang1);

    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-2");
    client2.sendSignOnRequest();
    client2.monitorPartition(lang2);

    // Populate: A, B, C
    Concept conceptA = new Concept(lang1, "Concept A", "ca", "ca");
    Concept conceptB = new Concept(lang1, "Concept B", "cb", "cb");
    Concept conceptC = new Concept(lang1, "Concept C", "cc", "cc");
    lang1.addElement(conceptA);
    lang1.addElement(conceptB);
    lang1.addElement(conceptC);

    assertEquals(Arrays.asList(conceptA, conceptB, conceptC), lang1.getElements());
    assertEquals(3, lang2.getElements().size());

    // Derive the MetaPointer for the elements containment from the server's stored data
    MetaPointer elementsMp =
        server
            .retrieve("MyRepo", List.of("lang-a"), 0)
            .get(0)
            .getContainments()
            .get(0)
            .getMetaPointer();

    // Move C (index 2) to index 0: expected [C, A, B]
    client1.sendMoveChildInSameContainmentCommand("lang-a", elementsMp, "cc", 2, 0);

    // client2 (the receiver) reflects the reordering
    List<io.lionweb.language.LanguageEntity> elements2 = lang2.getElements();
    assertEquals("cc", elements2.get(0).getID());
    assertEquals("ca", elements2.get(1).getID());
    assertEquals("cb", elements2.get(2).getID());

    // The server's stored state also reflects the reordering
    var containmentsAfter =
        server.retrieve("MyRepo", List.of("lang-a"), 0).get(0).getContainments();
    var storedChildren = containmentsAfter.get(0).getChildrenIds();
    assertEquals("cc", storedChildren.get(0));
    assertEquals("ca", storedChildren.get(1));
    assertEquals("cb", storedChildren.get(2));
  }

  /**
   * Moving a child within the same containment from a lower to a higher index also works correctly.
   */
  @Test
  public void moveChildInSameContainmentToHigherIndex() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang1 = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang1, ser);

    Language lang2 = (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", ser);
    Assertions.assertNotNull(lang2);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();
    client1.monitorPartition(lang1);

    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-2");
    client2.sendSignOnRequest();
    client2.monitorPartition(lang2);

    Concept conceptA = new Concept(lang1, "Concept A", "ca", "ca");
    Concept conceptB = new Concept(lang1, "Concept B", "cb", "cb");
    Concept conceptC = new Concept(lang1, "Concept C", "cc", "cc");
    lang1.addElement(conceptA);
    lang1.addElement(conceptB);
    lang1.addElement(conceptC);

    MetaPointer elementsMp =
        server
            .retrieve("MyRepo", List.of("lang-a"), 0)
            .get(0)
            .getContainments()
            .get(0)
            .getMetaPointer();

    // Move A (index 0) to index 2: expected [B, C, A]
    client1.sendMoveChildInSameContainmentCommand("lang-a", elementsMp, "ca", 0, 2);

    List<io.lionweb.language.LanguageEntity> elements2 = lang2.getElements();
    assertEquals("cb", elements2.get(0).getID());
    assertEquals("cc", elements2.get(1).getID());
    assertEquals("ca", elements2.get(2).getID());

    var storedChildren =
        server
            .retrieve("MyRepo", List.of("lang-a"), 0)
            .get(0)
            .getContainments()
            .get(0)
            .getChildrenIds();
    assertEquals("cb", storedChildren.get(0));
    assertEquals("cc", storedChildren.get(1));
    assertEquals("ca", storedChildren.get(2));
  }

  /**
   * A client can replace an existing child node with a new one at the same containment index using
   * ReplaceChild. Both clients see the updated child after the event is processed.
   */
  @Test
  public void replaceChild() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang1 = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang1, ser);

    Language lang2 = (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", ser);
    Assertions.assertNotNull(lang2);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();
    client1.monitorPartition(lang1);

    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-2");
    client2.sendSignOnRequest();
    client2.monitorPartition(lang2);

    Concept conceptA = new Concept(lang1, "Concept A", "ca", "ca");
    Concept conceptB = new Concept(lang1, "Concept B", "cb", "cb");
    lang1.addElement(conceptA);
    lang1.addElement(conceptB);

    assertEquals(Arrays.asList(conceptA, conceptB), lang1.getElements());
    assertEquals(2, lang2.getElements().size());
    assertEquals("ca", lang2.getElements().get(0).getID());

    MetaPointer elementsMp =
        server
            .retrieve("MyRepo", List.of("lang-a"), 0)
            .get(0)
            .getContainments()
            .get(0)
            .getMetaPointer();

    // Replace conceptA (at index 0) with a new concept C.
    // We create it inside an unmonitored scratch language so it has a proper (non-null) parent
    // and is not a partition, but no observer fires on lang1.
    Language scratchLang = new Language("scratch", "scratch-lang", "scratch-key");
    Concept conceptC = new Concept(scratchLang, "Concept C", "cc", "cc");
    client1.sendReplaceChildCommand("lang-a", elementsMp, 0, "ca", conceptC);

    // client1: local model still has 2 elements (ca was removed, cc added by client side)
    // The server state has the replacement
    assertTrue(server.listPartitionIDs("MyRepo").contains("lang-a"));
    // Verify via the server's stored data
    var serverNodes = server.retrieve("MyRepo", java.util.List.of("lang-a"), Integer.MAX_VALUE);
    assertTrue(serverNodes.stream().anyMatch(n -> "cc".equals(n.getID())));
    assertFalse(serverNodes.stream().anyMatch(n -> "ca".equals(n.getID())));

    // client2 received ChildReplaced event: the new child is at index 0
    assertEquals(2, lang2.getElements().size());
    assertEquals("cc", lang2.getElements().get(0).getID());
    assertEquals("cb", lang2.getElements().get(1).getID());
  }

  /**
   * A client can move a child from one parent node to a different parent node using
   * MoveChildFromOtherContainment. The server's stored state reflects the move.
   */
  @Test
  public void moveChildFromOtherContainment() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    // Two sibling partitions act as the old and new parents
    Language langA = new Language("Language A", "lang-a", "lang-a-key");
    Language langB = new Language("Language B", "lang-b", "lang-b-key");
    server.createPartition("MyRepo", langA, ser);
    server.createPartition("MyRepo", langB, ser);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client = new DeltaClient(channel, "my-client");
    client.sendSignOnRequest();
    client.monitorPartition(langA);
    client.monitorPartition(langB);

    // Add a concept to langA
    Concept concept = new Concept(langA, "Concept X", "cx", "cx");
    langA.addElement(concept);
    assertEquals(1, langA.getElements().size());
    assertEquals(0, langB.getElements().size());

    // Derive the MetaPointer for the elements containment from the server's stored data
    MetaPointer elementsMp =
        server
            .retrieve("MyRepo", List.of("lang-a"), 0)
            .get(0)
            .getContainments()
            .get(0)
            .getMetaPointer();

    // Move concept from langA to langB
    client.sendMoveChildFromOtherContainmentCommand(
        "lang-a", elementsMp, 0, "lang-b", elementsMp, 0, "cx");

    // Server state: concept is now under langB
    var serverNodes = server.retrieve("MyRepo", java.util.List.of("lang-a"), Integer.MAX_VALUE);
    assertFalse(serverNodes.stream().anyMatch(n -> "cx".equals(n.getID())));

    var serverNodesB = server.retrieve("MyRepo", java.util.List.of("lang-b"), Integer.MAX_VALUE);
    assertTrue(serverNodesB.stream().anyMatch(n -> "cx".equals(n.getID())));
  }
}
