package io.lionweb.serialization;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.LionWebVersion;
import io.lionweb.language.*;
import io.lionweb.model.*;
import io.lionweb.model.impl.DynamicAnnotationInstance;
import io.lionweb.model.impl.DynamicNode;
import io.lionweb.protobuf.PBChunk;
import io.lionweb.protobuf.PBLanguage;
import io.lionweb.protobuf.PBMetaPointer;
import io.lionweb.protobuf.PBNode;
import io.lionweb.serialization.data.*;
import io.lionweb.serialization.refsmm.ContainerNode;
import io.lionweb.serialization.refsmm.RefNode;
import io.lionweb.serialization.refsmm.RefsLanguage;
import io.lionweb.serialization.simplemath.IntLiteral;
import io.lionweb.serialization.simplemath.SimpleMathLanguage;
import io.lionweb.serialization.simplemath.Sum;
import io.lionweb.utils.ModelComparator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Testing various functionalities of ProtoBufSerialization. */
public class ProtobufSerializationTest extends SerializationTest {

  private void prepareDeserializationOfSimpleMath(ProtoBufSerialization protoBufSerialization) {
    protoBufSerialization.getClassifierResolver().registerLanguage(SimpleMathLanguage.INSTANCE);
    protoBufSerialization
        .getInstantiator()
        .registerCustomDeserializer(
            SimpleMathLanguage.INT_LITERAL.getID(),
            (concept, serializedNode, deserializedNodesByID, propertiesValues) ->
                new IntLiteral(
                    (Integer) propertiesValues.get(concept.getPropertyByName("value")),
                    serializedNode.getID()));
    protoBufSerialization
        .getInstantiator()
        .registerCustomDeserializer(
            SimpleMathLanguage.SUM.getID(),
            (concept, serializedNode, deserializedNodesByID, propertiesValues) -> {
              SerializedContainmentValue leftSCV =
                  serializedNode.getContainments().stream()
                      .filter(c -> c.getMetaPointer().getKey().equals("SimpleMath_Sum_left"))
                      .findFirst()
                      .get();
              IntLiteral left =
                  (IntLiteral) deserializedNodesByID.get(leftSCV.getChildrenIds().get(0));
              SerializedContainmentValue rightSCV =
                  serializedNode.getContainments().stream()
                      .filter(c -> c.getMetaPointer().getKey().equals("SimpleMath_Sum_right"))
                      .findFirst()
                      .get();
              IntLiteral right =
                  (IntLiteral) deserializedNodesByID.get(rightSCV.getChildrenIds().get(0));
              return new Sum(left, right, serializedNode.getID());
            });
  }

  @Test
  public void deserializeMultipleRoots() throws IOException {
    Sum sum1 = new Sum(new IntLiteral(1), new IntLiteral(2));
    Sum sum2 = new Sum(new IntLiteral(3), new IntLiteral(4));
    ProtoBufSerialization serialization = SerializationProvider.getStandardProtoBufSerialization();
    byte[] serialized = serialization.serializeTreesToByteArray(sum1, sum2);
    prepareDeserializationOfSimpleMath(serialization);
    List<Sum> deserialized =
        serialization.deserializeToNodes(serialized).stream()
            .filter(n -> n instanceof Sum)
            .map(n -> (Sum) n)
            .collect(Collectors.toList());
    assertEquals(Arrays.asList(sum1, sum2), deserialized);
  }

  @Test
  public void deserializeNodesWithoutIDsInTheRightOrder() throws IOException {
    IntLiteral il1 = new IntLiteral(1, null);
    IntLiteral il2 = new IntLiteral(2, null);
    IntLiteral il3 = new IntLiteral(3, null);
    IntLiteral il4 = new IntLiteral(4, null);
    ProtoBufSerialization serialization = SerializationProvider.getStandardProtoBufSerialization();
    byte[] serialized = serialization.serializeTreesToByteArray(il1, il2, il3, il4);
    prepareDeserializationOfSimpleMath(serialization);
    List<IntLiteral> deserialized =
        serialization.deserializeToNodes(serialized).stream()
            .map(n -> (IntLiteral) n)
            .collect(Collectors.toList());
    assertEquals(Arrays.asList(il1, il2, il3, il4), deserialized);
  }

  @Test
  public void deserializeTreesWithoutIDsInTheRightOrder() throws IOException {
    IntLiteral il1 = new IntLiteral(1, "int_1");
    IntLiteral il2 = new IntLiteral(2, "int_2");
    Sum sum1 = new Sum(il1, il2, null);
    IntLiteral il3 = new IntLiteral(3, "int_3");
    IntLiteral il4 = new IntLiteral(4, "int_4");
    Sum sum2 = new Sum(il3, il4, null);
    ProtoBufSerialization serialization = SerializationProvider.getStandardProtoBufSerialization();
    byte[] serialized = serialization.serializeTreesToByteArray(sum1, sum2);
    prepareDeserializationOfSimpleMath(serialization);
    List<Node> deserialized = serialization.deserializeToNodes(serialized);
    assertEquals(Arrays.asList(sum1, il1, il2, sum2, il3, il4), deserialized);
  }

  @Test
  public void deserializeTreesWithArbitraryOrderAndNullIDsInTheRightOrder() throws IOException {
    // handling multiple parents with null IDs require special care as they
    // are ambiguous (i.e., they cannot be distinguished by looking at their ID)
    IntLiteral il1 = new IntLiteral(1, "int_1");
    IntLiteral il2 = new IntLiteral(2, "int_2");
    Sum sum1 = new Sum(il1, il2, null);
    IntLiteral il3 = new IntLiteral(3, "int_3");
    IntLiteral il4 = new IntLiteral(4, "int_4");
    Sum sum2 = new Sum(il3, il4, null);
    ProtoBufSerialization serialization = SerializationProvider.getStandardProtoBufSerialization();
    byte[] serialized = serialization.serializeNodesToByteArray(il4, il1, sum1, il2, sum2, il3);
    prepareDeserializationOfSimpleMath(serialization);
    List<Node> deserialized = serialization.deserializeToNodes(serialized);
    assertEquals(Arrays.asList(il4, il1, sum1, il2, sum2, il3), deserialized);
  }

  // We should get a DeserializationException as we are unable to reassign the child with null ID
  @Test
  public void deserializeChildrenWithNullID() throws IOException {
    IntLiteral il1 = new IntLiteral(1, "int_1");
    IntLiteral il2 = new IntLiteral(2, null);
    Sum sum1 = new Sum(il1, il2, null);
    ProtoBufSerialization serialization = SerializationProvider.getStandardProtoBufSerialization();
    byte[] serialized = serialization.serializeNodesToByteArray(sum1, il1, il2);
    prepareDeserializationOfSimpleMath(serialization);
    assertThrows(
        DeserializationException.class, () -> serialization.deserializeToNodes(serialized));
  }

  private void prepareDeserializationOfRefMM(ProtoBufSerialization protoBufSerialization) {
    protoBufSerialization.getClassifierResolver().registerLanguage(RefsLanguage.INSTANCE);
    protoBufSerialization
        .getInstantiator()
        .registerCustomDeserializer(
            RefsLanguage.CONTAINER_NODE.getID(),
            (concept, serializedNode, deserializedNodesByID, propertiesValues) ->
                new ContainerNode(
                    (ContainerNode) propertiesValues.get(concept.getContainmentByName("contained")),
                    serializedNode.getID()));
    protoBufSerialization
        .getInstantiator()
        .registerCustomDeserializer(
            RefsLanguage.REF_NODE.getID(),
            (concept, serializedNode, deserializedNodesByID, propertiesValues) -> {
              return new RefNode(serializedNode.getID());
            });
  }

  @Test
  public void deadReferences() throws IOException {
    RefNode r1 = new RefNode();
    RefNode r2 = new RefNode();
    r1.setReferred(r2);
    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    byte[] serialized = protoBufSerialization.serializeNodesToByteArray(r1);
    prepareDeserializationOfRefMM(protoBufSerialization);
    assertThrows(
        DeserializationException.class, () -> protoBufSerialization.deserializeToNodes(serialized));
  }

  @Test
  public void referencesLoop() throws IOException {
    RefNode r1 = new RefNode();
    RefNode r2 = new RefNode();
    RefNode r3 = new RefNode();
    r1.setReferred(r2);
    r2.setReferred(r3);
    r3.setReferred(r1);
    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    byte[] serialized = protoBufSerialization.serializeNodesToByteArray(r1, r2, r3);
    prepareDeserializationOfRefMM(protoBufSerialization);
    List<Node> deserialized = protoBufSerialization.deserializeToNodes(serialized);
    assertEquals(Arrays.asList(r1, r2, r3), deserialized);
  }

  @Test
  public void containmentsLoop() throws IOException {
    ContainerNode c1 = new ContainerNode();
    ContainerNode c2 = new ContainerNode();
    c1.setContained(c2);
    c2.setContained(c1);
    c2.setParent(c1);
    c1.setParent(c2);

    assertEquals(c2, c1.getParent());
    assertEquals(c1, c2.getParent());
    assertEquals(Arrays.asList(c2), ClassifierInstanceUtils.getChildren(c1));
    assertEquals(Arrays.asList(c1), ClassifierInstanceUtils.getChildren(c2));

    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    byte[] serialized = protoBufSerialization.serializeNodesToByteArray(c1, c2);
    prepareDeserializationOfRefMM(protoBufSerialization);
    assertThrows(
        DeserializationException.class, () -> protoBufSerialization.deserializeToNodes(serialized));
  }

  @Test
  public void serializationOfLanguageVersionsWithImports() {
    Language myLanguage = new Language();
    myLanguage.setKey("myLanguage-key");
    myLanguage.setVersion("3");
    Concept myConcept = new Concept();
    myConcept.addImplementedInterface(LionCoreBuiltins.getINamed());
    myLanguage.addElement(myConcept);

    DynamicNode myInstance = new DynamicNode("instance-a", myConcept);
    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    SerializationChunk serializationChunk =
        protoBufSerialization.serializeNodesToSerializationChunk(myInstance);
    assertEquals(1, serializationChunk.getClassifierInstances().size());
    SerializedClassifierInstance serializedClassifierInstance =
        serializationChunk.getClassifierInstances().get(0);
    assertEquals("instance-a", serializedClassifierInstance.getID());
    assertEquals(1, serializedClassifierInstance.getProperties().size());
    SerializedPropertyValue serializedName = serializedClassifierInstance.getProperties().get(0);
    assertEquals(
        MetaPointer.get("LionCore-builtins", "2024.1", "LionCore-builtins-INamed-name"),
        serializedName.getMetaPointer());
  }

  @Test
  public void serializeAnnotations() {
    Language l = new Language("l", "l", "l", "1");
    Annotation a1 = new Annotation(l, "a1", "a1", "a1");
    Annotation a2 = new Annotation(l, "a2", "a2", "a2");
    Concept c = new Concept(l, "c", "c", "c");

    DynamicNode n1 = new DynamicNode("n1", c);
    AnnotationInstance a1_1 = new DynamicAnnotationInstance("a1_1", a1, n1);
    AnnotationInstance a1_2 = new DynamicAnnotationInstance("a1_2", a1, n1);
    AnnotationInstance a2_3 = new DynamicAnnotationInstance("a2_3", a2, n1);

    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    protoBufSerialization.enableDynamicNodes();
    SerializationChunk serializationChunk =
        protoBufSerialization.serializeNodesToSerializationChunk(n1);

    assertEquals(4, serializationChunk.getClassifierInstances().size());
    SerializedClassifierInstance serializedN1 = serializationChunk.getClassifierInstances().get(0);
    assertEquals("n1", serializedN1.getID());
    assertNull(serializedN1.getParentNodeID());
    assertEquals(Arrays.asList("a1_1", "a1_2", "a2_3"), serializedN1.getAnnotations());
    SerializedClassifierInstance serializedA1_1 =
        serializationChunk.getClassifierInstances().get(1);
    assertEquals("n1", serializedA1_1.getParentNodeID());

    List<ClassifierInstance<?>> deserialized =
        protoBufSerialization.deserializeSerializationChunk(serializationChunk);
    assertEquals(4, deserialized.size());
    assertInstancesAreEquals(a1_1, deserialized.get(1));
    assertEquals(deserialized.get(0), deserialized.get(1).getParent());
    assertInstancesAreEquals(a1_2, deserialized.get(2));
    assertEquals(deserialized.get(0), deserialized.get(2).getParent());
    assertInstancesAreEquals(a2_3, deserialized.get(3));
    assertEquals(deserialized.get(0), deserialized.get(3).getParent());
    assertInstancesAreEquals(n1, deserialized.get(0));
    assertEquals(
        Arrays.asList(deserialized.get(1), deserialized.get(2), deserialized.get(3)),
        deserialized.get(0).getAnnotations());
  }

  @Test
  public void serializeLanguage() {
    Language metaLang = new Language("metaLang", "metaLang", "metaLang", "1");
    Annotation metaAnn = new Annotation(metaLang, "metaAnn", "metaAnn", "metaAnn");

    Language l = new Language("l", "l", "l", "1");
    Annotation a1 = new Annotation(l, "a1", "a1", "a1");
    Annotation a2 = new Annotation(l, "a2", "a2", "a2");
    Concept c = new Concept(l, "c", "c", "c");
    DynamicAnnotationInstance ann = new DynamicAnnotationInstance("metaAnn_1", metaAnn, c);
    c.addAnnotation(ann);

    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    protoBufSerialization.enableDynamicNodes();
    SerializationChunk serializationChunk =
        protoBufSerialization.serializeTreeToSerializationChunk(l);

    assertEquals(5, serializationChunk.getClassifierInstances().size());
    SerializedClassifierInstance serializedL = serializationChunk.getClassifierInstances().get(0);
    assertEquals("l", serializedL.getID());
    assertNull(serializedL.getParentNodeID());

    SerializedClassifierInstance serializedC = serializationChunk.getInstanceByID("c");
    assertEquals("c", serializedC.getID());
    assertEquals(Arrays.asList("metaAnn_1"), serializedC.getAnnotations());

    protoBufSerialization.registerLanguage(metaLang);
    List<ClassifierInstance<?>> deserialized =
        protoBufSerialization.deserializeSerializationChunk(serializationChunk);
    assertEquals(5, deserialized.size());
    assertInstancesAreEquals(l, deserialized.get(0));
  }

  @Test
  public void serializationIncludeBuiltinsWhenUsedInProperties() {
    Language l = new Language("l", "l", "l", "1");
    Concept c = new Concept(l, "c", "c", "c");
    c.addFeature(
        Property.createRequired("foo", LionCoreBuiltins.getString())
            .setID("foo-id")
            .setKey("foo-key"));

    DynamicNode n1 = new DynamicNode("n1", c);
    ClassifierInstanceUtils.setPropertyValueByName(n1, "foo", "abc");

    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    SerializationChunk serializationChunk =
        protoBufSerialization.serializeNodesToSerializationChunk(n1);

    assertEquals(1, serializationChunk.getLanguages().size());
    assertSerializationChunkContainsLanguage(serializationChunk, l);
  }

  @Test
  public void deserializeLanguageToChunk() throws IOException {
    Language metaLang = new Language(LionWebVersion.v2023_1, "metaLang");
    metaLang.setID("metaLang");
    metaLang.setKey("metaLang");
    metaLang.setVersion("1");
    Annotation metaAnn = new Annotation(metaLang, "metaAnn", "metaAnn", "metaAnn");

    Language l = new Language(LionWebVersion.v2023_1, "l");
    l.setKey("l");
    l.setID("l");
    l.setVersion("1");
    Annotation a1 = new Annotation(l, "a1", "a1", "a1");
    Annotation a2 = new Annotation(l, "a2", "a2", "a2");
    Concept c = new Concept(l, "c", "c", "c");
    DynamicAnnotationInstance ann = new DynamicAnnotationInstance("metaAnn_1", metaAnn, c);
    c.addAnnotation(ann);

    ProtoBufSerialization serialization =
        SerializationProvider.getStandardProtoBufSerialization(LionWebVersion.v2023_1);
    serialization.enableDynamicNodes();
    SerializationChunk serializationChunk = serialization.serializeTreeToSerializationChunk(l);

    byte[] bytes = serialization.serializeToByteArray(serializationChunk);
    SerializationChunk deserializationChunk = serialization.deserializeToChunk(bytes);

    assertEquals(serializationChunk, deserializationChunk);
  }

  @Test
  public void serializeAnnotationsUsingLW2023_1() {
    Language l = new Language(LionWebVersion.v2023_1, "l");
    l.setKey("l");
    l.setID("l");
    l.setVersion("1");
    Annotation a1 = new Annotation(l, "a1", "a1", "a1");
    Annotation a2 = new Annotation(l, "a2", "a2", "a2");
    Concept c = new Concept(l, "c", "c", "c");

    DynamicNode n1 = new DynamicNode("n1", c);
    AnnotationInstance a1_1 = new DynamicAnnotationInstance("a1_1", a1, n1);
    AnnotationInstance a1_2 = new DynamicAnnotationInstance("a1_2", a1, n1);
    AnnotationInstance a2_3 = new DynamicAnnotationInstance("a2_3", a2, n1);

    ProtoBufSerialization serialization =
        SerializationProvider.getStandardProtoBufSerialization(LionWebVersion.v2023_1);
    serialization.enableDynamicNodes();
    SerializationChunk serializationChunk = serialization.serializeNodesToSerializationChunk(n1);

    assertEquals(4, serializationChunk.getClassifierInstances().size());
    SerializedClassifierInstance serializedN1 = serializationChunk.getClassifierInstances().get(0);
    assertEquals("n1", serializedN1.getID());
    assertNull(serializedN1.getParentNodeID());
    assertEquals(Arrays.asList("a1_1", "a1_2", "a2_3"), serializedN1.getAnnotations());
    SerializedClassifierInstance serializedA1_1 =
        serializationChunk.getClassifierInstances().get(1);
    assertEquals("n1", serializedA1_1.getParentNodeID());

    List<ClassifierInstance<?>> deserialized =
        serialization.deserializeSerializationChunk(serializationChunk);
    assertEquals(4, deserialized.size());
    assertInstancesAreEquals(n1, deserialized.get(0));
  }

  @Test
  public void serializeLanguage2023_1() {
    Language metaLang = new Language(LionWebVersion.v2023_1, "metaLang");
    metaLang.setID("metaLang");
    metaLang.setKey("metaLang");
    metaLang.setVersion("1");
    Annotation metaAnn = new Annotation(metaLang, "metaAnn", "metaAnn", "metaAnn");

    Language l = new Language(LionWebVersion.v2023_1, "l");
    l.setKey("l");
    l.setID("l");
    l.setVersion("1");
    Annotation a1 = new Annotation(l, "a1", "a1", "a1");
    Annotation a2 = new Annotation(l, "a2", "a2", "a2");
    Concept c = new Concept(l, "c", "c", "c");
    DynamicAnnotationInstance ann = new DynamicAnnotationInstance("metaAnn_1", metaAnn, c);
    c.addAnnotation(ann);

    ProtoBufSerialization serialization =
        SerializationProvider.getStandardProtoBufSerialization(LionWebVersion.v2023_1);
    serialization.enableDynamicNodes();
    SerializationChunk serializationChunk = serialization.serializeTreeToSerializationChunk(l);

    assertEquals(5, serializationChunk.getClassifierInstances().size());
    SerializedClassifierInstance serializedL = serializationChunk.getClassifierInstances().get(0);
    assertEquals("l", serializedL.getID());
    assertNull(serializedL.getParentNodeID());

    SerializedClassifierInstance serializedC = serializationChunk.getInstanceByID("c");
    assertEquals("c", serializedC.getID());
    assertEquals(Arrays.asList("metaAnn_1"), serializedC.getAnnotations());

    serialization.registerLanguage(metaLang);
    List<ClassifierInstance<?>> deserialized =
        serialization.deserializeSerializationChunk(serializationChunk);
    assertEquals(5, deserialized.size());
    ClassifierInstance<?> deserializedC = deserialized.get(3);
    assertInstancesAreEquals(c, deserializedC);
    assertEquals(deserialized.get(0), deserializedC.getParent());
    ClassifierInstance<?> deserializedAnn = deserialized.get(4);
    assertInstancesAreEquals(ann, deserializedAnn);
    assertEquals(deserializedC, deserializedAnn.getParent());
    assertEquals(Arrays.asList(deserializedAnn), deserializedC.getAnnotations());
  }

  @Test
  public void serializationWithNullReferencesAndProperties() throws IOException {
    Language myLanguage = new Language();
    myLanguage.setID("myLanguage");
    myLanguage.setName(null);
    myLanguage.setKey("myLanguage-key");
    myLanguage.setVersion("3");
    Concept myConcept = new Concept();
    myConcept.setID("myConcept");
    myConcept.addImplementedInterface(LionCoreBuiltins.getINamed());
    myConcept.setReferenceValues(
        myConcept.getClassifier().getReferenceByName("extends"),
        Arrays.asList(new ReferenceValue(null, null)));
    myLanguage.addElement(myConcept);

    ProtoBufSerialization serialization = SerializationProvider.getStandardProtoBufSerialization();
    byte[] bytes = serialization.serializeTreesToByteArray(myLanguage);
    assertInstancesAreEquals(myLanguage, serialization.deserializeToNodes(bytes).get(0));
  }

  @Test
  public void internedLanguagesAreConsistentAndReferencedByMetaPointersConceptWithNullKey() {
    // Build a minimal language with a concept implementing INamed to force inclusion of built-ins
    Language myLanguage = new Language();
    myLanguage.setKey("myLanguage-key");
    myLanguage.setVersion("3");
    Concept myConcept = new Concept();
    myConcept.addImplementedInterface(LionCoreBuiltins.getINamed());
    myLanguage.addElement(myConcept);

    DynamicNode myInstance = new DynamicNode("instance-a", myConcept);

    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    SerializationChunk serializationChunk =
        protoBufSerialization.serializeNodesToSerializationChunk(myInstance);

    PBChunk pbChunk = protoBufSerialization.serialize(serializationChunk);

    // There must be at least one language (most likely two: built-ins + our language)
    int languagesCount = pbChunk.getInternedLanguagesCount();
    assertTrue(languagesCount >= 1, "Expected at least one interned language");

    // Every metapointer must reference an existing language index
    for (PBMetaPointer mp : pbChunk.getInternedMetaPointersList()) {
      int languageIndex = mp.getLiLanguage();
      assertTrue(
          languageIndex >= 0 && languageIndex <= languagesCount,
          "PBMetaPointer.language index must be within interned languages table");

      // Language key/version must point to valid entries in the interned strings table
      PBLanguage lang = pbChunk.getInternedLanguages(languageIndex - 1);
      String key = pbChunk.getInternedStrings(lang.getSiKey() - 1);
      String version = pbChunk.getInternedStrings(lang.getSiVersion() - 1);
      assertNotNull("Language key must resolve to a string", key);
      assertNotNull("Language version must resolve to a string", version);
    }
  }

  @Test
  public void internedLanguagesAreConsistentAndReferencedByMetaPointersConceptWithProperKey() {
    // Build a minimal language with a concept implementing INamed to force inclusion of built-ins
    Language myLanguage = new Language();
    myLanguage.setKey("myLanguage-key");
    myLanguage.setVersion("3");
    Concept myConcept = new Concept();
    myConcept.setKey("myconceptkey");
    myConcept.addImplementedInterface(LionCoreBuiltins.getINamed());
    myLanguage.addElement(myConcept);

    DynamicNode myInstance = new DynamicNode("instance-a", myConcept);

    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    SerializationChunk serializationChunk =
        protoBufSerialization.serializeNodesToSerializationChunk(myInstance);

    PBChunk pbChunk = protoBufSerialization.serialize(serializationChunk);

    // There must be at least one language (most likely two: built-ins + our language)
    int languagesCount = pbChunk.getInternedLanguagesCount();
    assertTrue(languagesCount >= 1, "Expected at least one interned language");

    // Every metapointer must reference an existing language index
    for (PBMetaPointer mp : pbChunk.getInternedMetaPointersList()) {
      int languageIndex = mp.getLiLanguage();
      assertTrue(
          languageIndex >= 0 && languageIndex <= languagesCount,
          "PBMetaPointer.language index must be within interned languages table");

      // Language key/version must point to valid entries in the interned strings table
      PBLanguage lang = pbChunk.getInternedLanguages(languageIndex - 1);
      String key = pbChunk.getInternedStrings(lang.getSiKey() - 1);
      String version = pbChunk.getInternedStrings(lang.getSiVersion() - 1);
      assertNotNull("Language key must resolve to a string", key);
      assertNotNull("Language version must resolve to a string", version);

      // Metapointer key must also resolve to a string
      assertNotEquals(-1, mp.getSiKey());
      String mpKey = pbChunk.getInternedStrings(mp.getSiKey() - 1);
      assertNotNull("MetaPointer key must resolve to a string", mpKey);
    }
  }

  @Test
  public void deserializationFailsWhenMetaPointerReferencesMissingLanguage() {
    // Build a PBChunk with a meta pointer referencing a non-existent language index (0),
    // and no languages in interned_languages. This should trigger a DeserializationException.
    PBChunk.Builder chunkBuilder = PBChunk.newBuilder();
    chunkBuilder.setSerializationFormatVersion("test");

    // Add a dummy string at index 0, to use as a metapointer key
    chunkBuilder.addInternedStrings("dummy-key");

    // No interned_languages added intentionally.

    // Add a metapointer that references language index 0 (which doesn't exist)
    PBMetaPointer badMetaPointer =
        PBMetaPointer.newBuilder()
            .setLiLanguage(1)
            .setSiKey(1) // index into interned_strings ("dummy-key")
            .build();
    chunkBuilder.addInternedMetaPointers(badMetaPointer);

    // Add a node referencing the bad metapointer as classifier
    PBNode badNode =
        PBNode.newBuilder()
            .setSiId(1) // "dummy-key" as ID (not important for this test)
            .setMpiClassifier(0) // index of the bad metapointer
            .build();
    chunkBuilder.addNodes(badNode);

    PBChunk malformed = chunkBuilder.build();

    ProtoBufSerialization protoBufSerialization =
        SerializationProvider.getStandardProtoBufSerialization();
    // This call should throw due to missing language for the metapointer
    assertThrows(
        DeserializationException.class, () -> protoBufSerialization.deserializeToNodes(malformed));
  }

  @Test
  public void serializeNodesRejectsProxyNodes() {
    // Ensure ProxyNode is rejected by serializeNodesToByteArray
    io.lionweb.model.impl.ProxyNode proxy = new io.lionweb.model.impl.ProxyNode("proxy-id");
    ProtoBufSerialization serialization = SerializationProvider.getStandardProtoBufSerialization();
    // Casting to ClassifierInstance<?> could be implicit; using raw list to avoid generics issues
    assertThrows(
        IllegalArgumentException.class,
        () -> serialization.serializeNodesToByteArray(Collections.singletonList(proxy)));
  }

  @Test
  public void serializeEmptyFeatures_Protobuf() throws IOException {
    Library libA = new Library("libA", "libA");
    Book bookA = new Book("bookA");
    Library libB = new Library("libB", "libB");
    Book bookB = new Book("bookB");
    libB.addBook(bookB);
    Writer writer = new Writer("writer");
    bookB.setAuthor(writer);
    List<ClassifierInstance<?>> nodes = Arrays.asList(libA, bookA, libB, bookB, writer);

    ProtoBufSerialization standardSerialization =
        SerializationProvider.getStandardProtoBufSerialization(LionWebVersion.v2023_1);
    byte[] standardBytes = standardSerialization.serializeNodesToByteArray(nodes);
    SerializationChunk standardChunk = standardSerialization.deserializeToChunk(standardBytes);
    SerializedClassifierInstance standardLibA = standardChunk.getInstanceByID("libA");
    assertEquals(1, standardLibA.getContainments().size());
    SerializedClassifierInstance standardBookA = standardChunk.getInstanceByID("bookA");
    assertEquals(2, standardBookA.getProperties().size());
    assertEquals(1, standardBookA.getReferences().size());
    SerializedClassifierInstance standardLibB = standardChunk.getInstanceByID("libB");
    assertEquals(1, standardLibB.getContainments().size());
    SerializedClassifierInstance standardBookB = standardChunk.getInstanceByID("bookB");
    assertEquals(2, standardBookB.getProperties().size());
    assertEquals(1, standardBookB.getReferences().size());

    ProtoBufSerialization efficientSerialization =
        SerializationProvider.getEfficientProtoBufSerialization(LionWebVersion.v2023_1);
    byte[] efficientBytes = efficientSerialization.serializeNodesToByteArray(nodes);
    SerializationChunk efficientChunk = efficientSerialization.deserializeToChunk(efficientBytes);
    SerializedClassifierInstance efficientLibA = efficientChunk.getInstanceByID("libA");
    assertEquals(1, efficientLibA.getProperties().size());
    assertEquals(0, efficientLibA.getContainments().size());
    SerializedClassifierInstance efficientBookA = efficientChunk.getInstanceByID("bookA");
    assertEquals(0, efficientBookA.getProperties().size());
    assertEquals(0, efficientBookA.getReferences().size());
    SerializedClassifierInstance efficientLibB = efficientChunk.getInstanceByID("libB");
    assertEquals(1, efficientLibB.getProperties().size());
    assertEquals(1, efficientLibB.getContainments().size());
    SerializedClassifierInstance efficientBookB = efficientChunk.getInstanceByID("bookB");
    assertEquals(0, efficientBookB.getProperties().size());
    assertEquals(1, efficientBookB.getReferences().size());

    PBChunk pbChunk = PBChunk.parseFrom(new ByteArrayInputStream(efficientBytes));
    List<PBNode> nodesList = pbChunk.getNodesList();
    PBNode pbLibA = nodesList.get(0);
    assertEquals(1, pbLibA.getPropertiesCount());
    assertEquals(0, pbLibA.getContainmentsCount());
    PBNode pbBookA = nodesList.get(1);
    assertEquals(0, pbBookA.getPropertiesCount());
    assertEquals(0, pbBookA.getReferencesCount());
    PBNode pbLibB = nodesList.get(2);
    assertEquals(1, pbLibB.getPropertiesCount());
    assertEquals(1, pbLibB.getContainmentsCount());
    PBNode pbBookB = nodesList.get(3);
    assertEquals(0, pbBookB.getPropertiesCount());
    assertEquals(1, pbBookB.getReferencesCount());

    byte[] mixedBytes = efficientSerialization.serializeToByteArray(standardChunk);
    PBChunk pbMixedChunk = PBChunk.parseFrom(new ByteArrayInputStream(mixedBytes));
    List<PBNode> mixedNodesList = pbMixedChunk.getNodesList();
    PBNode pbMixedLibA = mixedNodesList.get(0);
    assertEquals(0, pbMixedLibA.getContainmentsCount());
    PBNode pbMixedBookA = mixedNodesList.get(1);
    assertEquals(0, pbMixedBookA.getPropertiesCount());
    assertEquals(0, pbMixedBookA.getReferencesCount());
    PBNode pbMixedLibB = mixedNodesList.get(2);
    assertEquals(1, pbMixedLibB.getContainmentsCount());
    PBNode pbMixedBookB = mixedNodesList.get(3);
    assertEquals(0, pbMixedBookB.getPropertiesCount());
    assertEquals(1, pbMixedBookB.getReferencesCount());

    efficientSerialization.registerLanguage(LibraryLanguage.LIBRARY_MM);
    efficientSerialization.enableDynamicNodes();
    List<ClassifierInstance<?>> deserializedChunk =
        efficientSerialization.deserializeSerializationChunk(efficientChunk);
    assertTrue(ModelComparator.areEquivalent(nodes, deserializedChunk));
    List<Node> deserializedBytes = efficientSerialization.deserializeToNodes(efficientBytes);
    assertTrue(ModelComparator.areEquivalent(nodes, deserializedBytes));
  }

  private void assertSerializationChunkContainsLanguage(
      SerializationChunk serializationChunk, Language language) {
    assertTrue(
        serializationChunk.getLanguages().stream()
            .anyMatch(
                entry ->
                    entry.getKey().equals(language.getKey())
                        && entry.getVersion().equals(language.getVersion())));
  }
}
