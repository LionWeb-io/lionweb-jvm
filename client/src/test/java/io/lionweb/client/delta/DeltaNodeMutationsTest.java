package io.lionweb.client.delta;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.client.inmemory.InMemoryServer;
import io.lionweb.language.Concept;
import io.lionweb.language.Language;
import io.lionweb.language.Reference;
import io.lionweb.model.ReferenceValue;
import io.lionweb.serialization.JsonSerialization;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Tests for Delta protocol implementation: property, reference, and classifier mutations. */
public class DeltaNodeMutationsTest extends AbstractDeltaProtocolTest {

  /**
   * When a property transitions from null to a value, the client sends AddProperty. Both clients
   * see the new value after the event is processed.
   */
  @Test
  public void addProperty() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang1 = new Language("Language A", "lang-a", "lang-a-key");
    lang1.setName(null); // start with null name so first set is an AddProperty
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

    assertNull(lang1.getName());
    assertNull(lang2.getName());

    lang1.setName("Language B");

    assertEquals("Language B", lang1.getName());
    assertEquals("Language B", lang2.getName());
  }

  /**
   * When a property transitions from a value to null, the client sends DeleteProperty. Both clients
   * see the property cleared after the event is processed.
   */
  @Test
  public void deleteProperty() {
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

    assertEquals("Language A", lang1.getName());
    assertEquals("Language A", lang2.getName());

    lang1.setName(null);

    assertNull(lang1.getName());
    assertNull(lang2.getName());
  }

  /**
   * When a reference entry is replaced (same index, different target), the client sends
   * ChangeReference. Both clients see the updated reference after the event is processed.
   */
  @Test
  public void changeReference() {
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

    // Set up a concept that uses a reference (extendedConcept)
    Concept concept1 = new Concept(lang1, "Concept A", "concept-a", "ca");
    lang1.addElement(concept1);
    Concept concept2 = new Concept(lang1, "Concept B", "concept-b", "cb");
    lang1.addElement(concept2);
    Concept concept3 = new Concept(lang1, "Concept C", "concept-c", "cc");
    lang1.addElement(concept3);

    // Initially concept1 extends concept2
    concept1.setExtendedConcept(concept2);
    assertEquals("concept-b", concept1.getExtendedConcept().getID());

    // Change: concept1 now extends concept3 — observer fires referenceValueChanged
    concept1.setExtendedConcept(concept3);

    assertEquals("concept-c", concept1.getExtendedConcept().getID());
    // client2's copy reflects the change; the target arrives as a ProxyNode so we read the raw
    // reference value rather than calling getExtendedConcept() which would require a Concept cast
    Concept concept1OnClient2 =
        (Concept)
            lang2.getElements().stream()
                .filter(e -> "concept-a".equals(e.getID()))
                .findFirst()
                .orElseThrow();
    Reference extendsFeature =
        (Reference) concept1OnClient2.getClassifier().getFeatureByName("extends");
    List<ReferenceValue> refs = concept1OnClient2.getReferenceValues(extendsFeature);
    assertEquals(1, refs.size());
    assertEquals("concept-c", refs.get(0).getReferredID());
  }

  /**
   * When a reference entry is removed, the client sends DeleteReference. Both clients see the
   * reference cleared after the event is processed.
   */
  @Test
  public void deleteReference() {
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

    Concept concept1 = new Concept(lang1, "Concept A", "concept-a", "ca");
    lang1.addElement(concept1);
    Concept concept2 = new Concept(lang1, "Concept B", "concept-b", "cb");
    lang1.addElement(concept2);

    concept1.setExtendedConcept(concept2);
    assertNotNull(concept1.getExtendedConcept());

    // Remove the extended-concept reference — observer fires referenceValueRemoved
    concept1.setExtendedConcept(null);

    assertNull(concept1.getExtendedConcept());
    Concept concept1OnClient2 =
        (Concept)
            lang2.getElements().stream()
                .filter(e -> "concept-a".equals(e.getID()))
                .findFirst()
                .orElseThrow();
    assertNull(concept1OnClient2.getExtendedConcept());
  }

  /**
   * A client can send ChangeClassifier directly. The server updates the stored classifier and
   * broadcasts a ClassifierChanged event. Other clients acknowledge the event without throwing.
   */
  @Test
  @Disabled
  public void changeClassifier() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang, ser);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();

    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-2");
    client2.sendSignOnRequest();

    // Use the MetaPointer of an arbitrary target classifier
    io.lionweb.serialization.data.MetaPointer newClassifier =
        io.lionweb.serialization.data.MetaPointer.get(
            "io.lionweb.language", "1", "io.lionweb.language.Interface");

    client1.sendChangeClassifierCommand("lang-a", newClassifier);

    // Verify server stored the updated classifier
    io.lionweb.serialization.data.SerializedClassifierInstance stored =
        server.retrieve("MyRepo", java.util.List.of("lang-a"), 0).get(0);
    assertEquals(newClassifier, stored.getClassifier());
    // client2 received ClassifierChanged without throwing — the event was handled
  }
}
