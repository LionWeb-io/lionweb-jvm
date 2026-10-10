package io.lionweb.client.delta;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.client.inmemory.InMemoryServer;
import io.lionweb.language.Annotation;
import io.lionweb.language.Language;
import io.lionweb.model.impl.DynamicAnnotationInstance;
import io.lionweb.serialization.JsonSerialization;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for Delta protocol implementation: annotation operations.
 *
 * <p>Covers: AddAnnotation / AnnotationAdded, DeleteAnnotation / AnnotationDeleted,
 * MoveAnnotationInSameParent, and MoveAnnotationFromOtherParent, both when the commands are
 * produced by partition monitoring and when they are sent through the explicit {@code send*Command}
 * API of {@link DeltaClient}.
 */
public class DeltaAnnotationsTest extends AbstractDeltaProtocolTest {

  private static final Language ANN_LANG =
      new Language("AnnotationTestLang", "ann-test-lang", "ann-test-lang-key");
  private static final Annotation COMMENT_ANN =
      new Annotation(ANN_LANG, "Comment", "comment-ann-id", "comment-ann-key");

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private DeltaClient signedOnClient(DeltaChannel channel, String clientId) {
    DeltaClient client = new DeltaClient(channel, clientId);
    client.registerLanguage(ANN_LANG);
    client.sendSignOnRequest();
    return client;
  }

  // ---------------------------------------------------------------------------
  // Add annotation
  // ---------------------------------------------------------------------------

  /**
   * When a client adds an annotation to a monitored node, the observer sends AddAnnotation. The
   * server stores it and broadcasts AnnotationAdded to the other client, which attaches the
   * annotation to its local copy of the node.
   */
  @Test
  public void addAnnotation() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang1 = new Language("LangA", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang1, ser);

    Language lang2 = (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", ser);
    Assertions.assertNotNull(lang2);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client1 = signedOnClient(channel, "my-client-1");
    client1.monitorPartition(lang1);

    DeltaClient client2 = signedOnClient(prepareChannel(server), "my-client-2");
    client2.monitorPartition(lang2);

    assertTrue(lang1.getAnnotations().isEmpty());
    assertTrue(lang2.getAnnotations().isEmpty());

    // Add annotation via the model API — the observer fires and sends AddAnnotation
    DynamicAnnotationInstance ann = new DynamicAnnotationInstance("ann-1", COMMENT_ANN);
    lang1.addAnnotation(ann);

    assertEquals(1, lang1.getAnnotations().size());
    assertEquals("ann-1", lang1.getAnnotations().get(0).getID());

    // client2 received AnnotationAdded and attached the annotation to its copy
    assertEquals(1, lang2.getAnnotations().size());
    assertEquals("ann-1", lang2.getAnnotations().get(0).getID());

    // Server stores the annotation node
    var serverNodes = server.retrieve("MyRepo", List.of("lang-a"), Integer.MAX_VALUE);
    assertTrue(serverNodes.stream().anyMatch(n -> "ann-1".equals(n.getID())));
    var langANode =
        serverNodes.stream().filter(n -> "lang-a".equals(n.getID())).findFirst().orElseThrow();
    assertEquals(List.of("ann-1"), langANode.getAnnotations());
  }

  // ---------------------------------------------------------------------------
  // Delete annotation
  // ---------------------------------------------------------------------------

  /**
   * When a client removes an annotation from a monitored node, the observer sends DeleteAnnotation.
   * The server removes it and broadcasts AnnotationDeleted to the other client.
   */
  @Test
  public void deleteAnnotation() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang1 = new Language("LangA", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang1, ser);

    Language lang2 = (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", ser);
    Assertions.assertNotNull(lang2);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client1 = signedOnClient(channel, "my-client-1");
    client1.monitorPartition(lang1);

    DeltaClient client2 = signedOnClient(prepareChannel(server), "my-client-2");
    client2.monitorPartition(lang2);

    // Add annotation first
    DynamicAnnotationInstance ann = new DynamicAnnotationInstance("ann-1", COMMENT_ANN);
    lang1.addAnnotation(ann);
    assertEquals(1, lang1.getAnnotations().size());
    assertEquals(1, lang2.getAnnotations().size());

    // Remove the annotation via the model API
    lang1.removeAnnotation(ann);

    assertEquals(0, lang1.getAnnotations().size());
    assertEquals(0, lang2.getAnnotations().size());

    // Server no longer stores the annotation
    var serverNodes = server.retrieve("MyRepo", List.of("lang-a"), Integer.MAX_VALUE);
    assertFalse(serverNodes.stream().anyMatch(n -> "ann-1".equals(n.getID())));
  }

  // ---------------------------------------------------------------------------
  // Move annotation in same parent
  // ---------------------------------------------------------------------------

  /**
   * A client can reorder annotations on a node using MoveAnnotationInSameParent. The server updates
   * its stored annotation list. Client-side ordering is not verified since ClassifierInstance does
   * not support indexed annotation insertion.
   */
  @Test
  public void moveAnnotationInSameParent() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang = new Language("LangA", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang, ser);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = signedOnClient(channel, "my-client");
    client.monitorPartition(lang);

    // Add three annotations
    lang.addAnnotation(new DynamicAnnotationInstance("ann-1", COMMENT_ANN));
    lang.addAnnotation(new DynamicAnnotationInstance("ann-2", COMMENT_ANN));
    lang.addAnnotation(new DynamicAnnotationInstance("ann-3", COMMENT_ANN));

    var storedBefore = server.retrieve("MyRepo", List.of("lang-a"), 0).get(0).getAnnotations();
    assertEquals(List.of("ann-1", "ann-2", "ann-3"), storedBefore);

    // Move ann-1 (index 0) to index 2: expected [ann-2, ann-3, ann-1]
    client.sendMoveAnnotationInSameParentCommand("lang-a", "ann-1", 0, 2);

    var storedAfter = server.retrieve("MyRepo", List.of("lang-a"), 0).get(0).getAnnotations();
    assertEquals(List.of("ann-2", "ann-3", "ann-1"), storedAfter);
  }

  // ---------------------------------------------------------------------------
  // Explicit send*Command API
  // ---------------------------------------------------------------------------

  /**
   * A client can add an annotation using the explicit {@link DeltaClient#sendAddAnnotationCommand}
   * API. The server stores the annotation and broadcasts AnnotationAdded to the other client, which
   * attaches the annotation to its local copy of the node.
   */
  @Test
  public void sendAddAnnotationCommand() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang1 = new Language("LangA", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang1, ser);

    Language lang2 = (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", ser);
    Assertions.assertNotNull(lang2);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client1 = signedOnClient(channel, "my-client-1");
    client1.monitorPartition(lang1);

    DeltaClient client2 = signedOnClient(channel, "my-client-2");
    client2.monitorPartition(lang2);

    // The annotation is not attached to any local node: only the command is sent
    DynamicAnnotationInstance ann = new DynamicAnnotationInstance("ann-1", COMMENT_ANN);
    client1.sendAddAnnotationCommand("lang-a", ann, 0);

    // Server stores the annotation node, attached to lang-a
    var serverNodes = server.retrieve("MyRepo", List.of("lang-a"), Integer.MAX_VALUE);
    var storedAnn =
        serverNodes.stream().filter(n -> "ann-1".equals(n.getID())).findFirst().orElseThrow();
    assertEquals("lang-a", storedAnn.getParentNodeID());
    var langANode =
        serverNodes.stream().filter(n -> "lang-a".equals(n.getID())).findFirst().orElseThrow();
    assertEquals(List.of("ann-1"), langANode.getAnnotations());

    // client2 received AnnotationAdded and attached the annotation to its copy
    assertEquals(1, lang2.getAnnotations().size());
    assertEquals("ann-1", lang2.getAnnotations().get(0).getID());
  }

  /**
   * The index passed to {@link DeltaClient#sendAddAnnotationCommand} determines where the server
   * inserts the annotation in the parent's annotation list.
   */
  @Test
  public void sendAddAnnotationCommandAtIndex() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang = new Language("LangA", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang, ser);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = signedOnClient(channel, "my-client");
    client.monitorPartition(lang);

    lang.addAnnotation(new DynamicAnnotationInstance("ann-1", COMMENT_ANN));
    lang.addAnnotation(new DynamicAnnotationInstance("ann-2", COMMENT_ANN));
    assertEquals(
        List.of("ann-1", "ann-2"),
        server.retrieve("MyRepo", List.of("lang-a"), 0).get(0).getAnnotations());

    // Insert ann-3 between ann-1 and ann-2
    client.sendAddAnnotationCommand(
        "lang-a", new DynamicAnnotationInstance("ann-3", COMMENT_ANN), 1);

    assertEquals(
        List.of("ann-1", "ann-3", "ann-2"),
        server.retrieve("MyRepo", List.of("lang-a"), 0).get(0).getAnnotations());
  }

  /**
   * A client can delete an annotation using the explicit {@link
   * DeltaClient#sendDeleteAnnotationCommand} API. The server removes it and broadcasts
   * AnnotationDeleted to the other client, which removes it from its local copy of the node.
   */
  @Test
  public void sendDeleteAnnotationCommand() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang1 = new Language("LangA", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang1, ser);

    Language lang2 = (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", ser);
    Assertions.assertNotNull(lang2);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client1 = signedOnClient(channel, "my-client-1");
    client1.monitorPartition(lang1);

    DeltaClient client2 = signedOnClient(channel, "my-client-2");
    client2.monitorPartition(lang2);

    // Add two annotations via the model API, so that all parties know about them
    lang1.addAnnotation(new DynamicAnnotationInstance("ann-1", COMMENT_ANN));
    lang1.addAnnotation(new DynamicAnnotationInstance("ann-2", COMMENT_ANN));
    assertEquals(2, lang2.getAnnotations().size());

    // Delete ann-1 (index 0) via the explicit API
    client1.sendDeleteAnnotationCommand("lang-a", 0, "ann-1");

    // Server no longer stores ann-1
    var serverNodes = server.retrieve("MyRepo", List.of("lang-a"), Integer.MAX_VALUE);
    assertFalse(serverNodes.stream().anyMatch(n -> "ann-1".equals(n.getID())));
    var langANode =
        serverNodes.stream().filter(n -> "lang-a".equals(n.getID())).findFirst().orElseThrow();
    assertEquals(List.of("ann-2"), langANode.getAnnotations());

    // client2 received AnnotationDeleted and removed ann-1 from its copy
    assertEquals(1, lang2.getAnnotations().size());
    assertEquals("ann-2", lang2.getAnnotations().get(0).getID());
  }

  /**
   * A client can move an annotation from one node to another using the explicit {@link
   * DeltaClient#sendMoveAnnotationFromOtherParentCommand} API. The server updates the annotation
   * lists of both nodes and the parent of the annotation. Only a single client is used, since
   * applying AnnotationMovedFromOtherParent on a receiving client is not supported yet.
   */
  @Test
  public void sendMoveAnnotationFromOtherParentCommand() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language langA = new Language("LangA", "lang-a", "lang-a-key");
    Language langB = new Language("LangB", "lang-b", "lang-b-key");
    server.createPartition("MyRepo", langA, ser);
    server.createPartition("MyRepo", langB, ser);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = signedOnClient(channel, "my-client");
    client.monitorPartition(langA);
    client.monitorPartition(langB);

    langA.addAnnotation(new DynamicAnnotationInstance("ann-1", COMMENT_ANN));
    langB.addAnnotation(new DynamicAnnotationInstance("ann-2", COMMENT_ANN));
    assertEquals(
        List.of("ann-1"), server.retrieve("MyRepo", List.of("lang-a"), 0).get(0).getAnnotations());
    assertEquals(
        List.of("ann-2"), server.retrieve("MyRepo", List.of("lang-b"), 0).get(0).getAnnotations());

    // Move ann-1 from lang-a (index 0) to lang-b (index 0): expected lang-b = [ann-1, ann-2]
    client.sendMoveAnnotationFromOtherParentCommand("lang-a", "lang-b", "ann-1", 0, 0);

    assertEquals(
        List.of(), server.retrieve("MyRepo", List.of("lang-a"), 0).get(0).getAnnotations());
    assertEquals(
        List.of("ann-1", "ann-2"),
        server.retrieve("MyRepo", List.of("lang-b"), 0).get(0).getAnnotations());

    // The annotation node is now stored under lang-b
    var serverNodesA = server.retrieve("MyRepo", List.of("lang-a"), Integer.MAX_VALUE);
    assertFalse(serverNodesA.stream().anyMatch(n -> "ann-1".equals(n.getID())));
    var serverNodesB = server.retrieve("MyRepo", List.of("lang-b"), Integer.MAX_VALUE);
    var storedAnn =
        serverNodesB.stream().filter(n -> "ann-1".equals(n.getID())).findFirst().orElseThrow();
    assertEquals("lang-b", storedAnn.getParentNodeID());
  }
}
