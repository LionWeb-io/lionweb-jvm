package io.lionweb.client.delta;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.client.inmemory.InMemoryServer;
import io.lionweb.language.Annotation;
import io.lionweb.language.Language;
import io.lionweb.model.AnnotationInstance;
import io.lionweb.model.ClassifierInstance;
import io.lionweb.model.impl.DynamicAnnotationInstance;
import io.lionweb.serialization.JsonSerialization;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for Delta protocol implementation: annotation operations.
 *
 * <p>Covers: AddAnnotation / AnnotationAdded, DeleteAnnotation / AnnotationDeleted,
 * ReplaceAnnotation / AnnotationReplaced, and MoveAnnotationInSameParent.
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

    DeltaClient client2 = signedOnClient(channel, "my-client-2");
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

    DeltaClient client2 = signedOnClient(channel, "my-client-2");
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
  // Replace annotation
  // ---------------------------------------------------------------------------

  /**
   * A client can replace an annotation using ReplaceAnnotation. The server replaces it in its
   * stored state and broadcasts AnnotationReplaced to the other client, which replaces the
   * annotation in its local copy at the same index, keeping the other annotations in place.
   */
  @Test
  public void replaceAnnotation() {
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

    lang1.addAnnotation(new DynamicAnnotationInstance("ann-1", COMMENT_ANN));
    lang1.addAnnotation(new DynamicAnnotationInstance("ann-2", COMMENT_ANN));
    lang1.addAnnotation(new DynamicAnnotationInstance("ann-3", COMMENT_ANN));
    assertEquals(List.of("ann-1", "ann-2", "ann-3"), annotationIds(lang2));

    // Replace ann-2 (index 1) with ann-new
    client1.sendReplaceAnnotationCommand(
        "lang-a", 1, "ann-2", new DynamicAnnotationInstance("ann-new", COMMENT_ANN));

    // Server stores the replacement at the same index and drops the replaced annotation
    var serverNodes = server.retrieve("MyRepo", List.of("lang-a"), Integer.MAX_VALUE);
    assertTrue(serverNodes.stream().anyMatch(n -> "ann-new".equals(n.getID())));
    assertFalse(serverNodes.stream().anyMatch(n -> "ann-2".equals(n.getID())));
    var langANode =
        serverNodes.stream().filter(n -> "lang-a".equals(n.getID())).findFirst().orElseThrow();
    assertEquals(List.of("ann-1", "ann-new", "ann-3"), langANode.getAnnotations());

    // client2 received AnnotationReplaced and replaced the annotation in place
    assertEquals(List.of("ann-1", "ann-new", "ann-3"), annotationIds(lang2));
    assertSame(lang2, lang2.getAnnotations().get(1).getParent());
  }

  /**
   * An annotation received through AnnotationReplaced is tracked by the client, so later events
   * targeting it (here, a further replacement) are applied too.
   */
  @Test
  public void replacedAnnotationIsMonitored() {
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

    lang1.addAnnotation(new DynamicAnnotationInstance("ann-1", COMMENT_ANN));
    client1.sendReplaceAnnotationCommand(
        "lang-a", 0, "ann-1", new DynamicAnnotationInstance("ann-2", COMMENT_ANN));
    assertEquals(List.of("ann-2"), annotationIds(lang2));

    // A third client annotates the replacement: client2 must apply it to the new annotation
    DeltaClient client3 = signedOnClient(channel, "my-client-3");
    client3.sendAddAnnotationCommand(
        "ann-2", new DynamicAnnotationInstance("ann-on-ann", COMMENT_ANN), 0);
    assertEquals(List.of("ann-on-ann"), annotationIds(lang2.getAnnotations().get(0)));
  }

  private static List<String> annotationIds(ClassifierInstance<?> instance) {
    return instance.getAnnotations().stream()
        .map(AnnotationInstance::getID)
        .collect(Collectors.toList());
  }
}
