package io.lionweb.serialization;

import com.google.common.collect.Sets;
import io.lionweb.LionWebVersion;
import io.lionweb.api.ClassifierInstanceResolver;
import io.lionweb.api.CompositeClassifierInstanceResolver;
import io.lionweb.api.LocalClassifierInstanceResolver;
import io.lionweb.language.*;
import io.lionweb.model.*;
import io.lionweb.model.impl.AbstractClassifierInstance;
import io.lionweb.model.impl.ProxyNode;
import io.lionweb.serialization.data.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This is a common ancestor to all Serialization classes. It contains logic to move between the
 * actual Nodes and the intermediate format (SerializationChunk). The step between the
 * SerializationChunk and the actual physical formats is done in other classes.
 */
public abstract class AbstractSerialization {

  /** You should use LionWebVersion.currentVersion.getVersionString() instead. */
  @Deprecated
  public static final String DEFAULT_SERIALIZATION_FORMAT =
      LionWebVersion.currentVersion.getVersionString();

  protected ClassifierResolver classifierResolver;
  protected Instantiator instantiator;
  protected DataTypesValuesSerialization dataTypesValuesSerialization;

  protected LocalClassifierInstanceResolver instanceResolver;

  /**
   * This guides what we do when deserializing a sub-tree and not being able to resolve the parent.
   */
  protected UnavailableNodePolicy unavailableParentPolicy = UnavailableNodePolicy.THROW_ERROR;

  /**
   * This guides what we do when deserializing a sub-tree and not being able to resolve the
   * children.
   */
  protected UnavailableNodePolicy unavailableChildrenPolicy = UnavailableNodePolicy.THROW_ERROR;

  /**
   * This guides what we do when deserializing a sub-tree and not being able to resolve a reference
   * target.
   */
  protected UnavailableNodePolicy unavailableReferenceTargetPolicy =
      UnavailableNodePolicy.THROW_ERROR;

  /**
   * Whether we include (true) or omit (false) empty features in serialization.
   */
  protected boolean serializeEmptyFeatures = true;

  private final @Nonnull LionWebVersion lionWebVersion;

  protected boolean builtinsReferenceDangling = false;

  protected AbstractSerialization() {
    this(LionWebVersion.currentVersion);
  }

  protected AbstractSerialization(@Nonnull LionWebVersion lionWebVersion) {
    Objects.requireNonNull(lionWebVersion, "lionWebVersion should not be null");
    this.lionWebVersion = lionWebVersion;
    // prevent public access
    classifierResolver = new ClassifierResolver();
    instantiator = new Instantiator();
    dataTypesValuesSerialization = new DataTypesValuesSerialization();
    instanceResolver = new LocalClassifierInstanceResolver();
  }

  //
  // Configuration
  //

  public ClassifierResolver getClassifierResolver() {
    return classifierResolver;
  }

  public void setClassifierResolver(ClassifierResolver classifierResolver) {
    this.classifierResolver = classifierResolver;
  }

  public void setInstantiator(Instantiator instantiator) {
    this.instantiator = instantiator;
  }

  public void setPrimitiveValuesSerialization(
      DataTypesValuesSerialization dataTypesValuesSerialization) {
    this.dataTypesValuesSerialization = dataTypesValuesSerialization;
  }

  public void setInstanceResolver(LocalClassifierInstanceResolver instanceResolver) {
    this.instanceResolver = instanceResolver;
  }

  public Instantiator getInstantiator() {
    return instantiator;
  }

  public DataTypesValuesSerialization getPrimitiveValuesSerialization() {
    return dataTypesValuesSerialization;
  }

  public LocalClassifierInstanceResolver getInstanceResolver() {
    return instanceResolver;
  }

  public void enableDynamicNodes() {
    instantiator.enableDynamicNodes();
    dataTypesValuesSerialization.enableDynamicNodes();
  }

  public @Nonnull UnavailableNodePolicy getUnavailableParentPolicy() {
    return this.unavailableParentPolicy;
  }

  public @Nonnull UnavailableNodePolicy getUnavailableReferenceTargetPolicy() {
    return this.unavailableReferenceTargetPolicy;
  }

  public @Nonnull UnavailableNodePolicy getUnavailableChildrenPolicy() {
    return this.unavailableChildrenPolicy;
  }

  public boolean shouldSerializeEmptyFeatures() {
    return serializeEmptyFeatures;
  }

  public void setAllUnavailabilityPolicies(@Nonnull UnavailableNodePolicy unavailabilityPolicy) {
    Objects.requireNonNull(unavailabilityPolicy);
    this.unavailableChildrenPolicy = unavailabilityPolicy;
    this.unavailableReferenceTargetPolicy = unavailabilityPolicy;
    this.unavailableParentPolicy = unavailabilityPolicy;
  }

  public void setUnavailableParentPolicy(@Nonnull UnavailableNodePolicy unavailableParentPolicy) {
    Objects.requireNonNull(unavailableParentPolicy);
    this.unavailableParentPolicy = unavailableParentPolicy;
  }

  public void setUnavailableChildrenPolicy(
      @Nonnull UnavailableNodePolicy unavailableChildrenPolicy) {
    Objects.requireNonNull(unavailableChildrenPolicy);
    this.unavailableChildrenPolicy = unavailableChildrenPolicy;
  }

  public void setUnavailableReferenceTargetPolicy(
      @Nonnull UnavailableNodePolicy unavailableReferenceTargetPolicy) {
    Objects.requireNonNull(unavailableReferenceTargetPolicy);
    this.unavailableReferenceTargetPolicy = unavailableReferenceTargetPolicy;
  }

  public void setSerializeEmptyFeatures(boolean serializeEmptyFeatures) {
    this.serializeEmptyFeatures = serializeEmptyFeatures;
  }

  public void registerLanguage(Language language) {
    getClassifierResolver().registerLanguage(language);
    getPrimitiveValuesSerialization().registerLanguage(language);
    instanceResolver.addTree(language);
  }

  public void makeBuiltinsReferenceDangling() {
    this.builtinsReferenceDangling = true;
  }

  //
  // Serialization to chunk
  //

  public SerializationChunk serializeTreeToSerializationChunk(ClassifierInstance<?> root) {
    Set<ClassifierInstance<?>> classifierInstances = new LinkedHashSet<>();
    ClassifierInstance.collectSelfAndDescendants(root, true, classifierInstances);
    return serializeNodesToSerializationChunk(classifierInstances);
  }

  public SerializationChunk serializeTreesToSerializationChunk(
      List<? extends ClassifierInstance<?>> roots) {
    Set<ClassifierInstance<?>> classifierInstances = new LinkedHashSet<>();
    roots.forEach(
        root -> ClassifierInstance.collectSelfAndDescendants(root, true, classifierInstances));
    return serializeNodesToSerializationChunk(classifierInstances);
  }

  public SerializationChunk serializeNodesToSerializationChunk(
      Collection<ClassifierInstance<?>> classifierInstances) {
    SerializationChunk serializationChunk = new SerializationChunk();
    serializationChunk.setSerializationFormatVersion(lionWebVersion.getVersionString());
    SerializationStatus serializationStatus = new SerializationStatus();
    Consumer<Language> languageConsumer = this::considerLanguageDuringSerialization;
    for (ClassifierInstance<?> classifierInstance : classifierInstances) {
      Objects.requireNonNull(classifierInstance, "nodes should not contain null values");
      serializationChunk.addClassifierInstance(
          serializeNode(classifierInstance, serializationStatus));
      classifierInstance.getAnnotations().stream()
          .filter(a -> !classifierInstances.contains(a))
          .forEach(
              annotationInstance -> {
                serializationChunk.addClassifierInstance(
                    serializeAnnotationInstance(annotationInstance, serializationStatus));
              });
      serializationStatus.considerLanguageDuringSerialization(
          languageConsumer, classifierInstance.getClassifier().getLanguage());
    }
    serializationChunk.populateUsedLanguages();
    return serializationChunk;
  }

  private void considerLanguageDuringSerialization(Language language) {
    registerLanguage(language);
  }

  public SerializationChunk serializeNodesToSerializationChunk(
      ClassifierInstance<?>... classifierInstances) {
    return serializeNodesToSerializationChunk(Arrays.asList(classifierInstances));
  }

  private SerializedClassifierInstance serializeNode(
      @Nonnull ClassifierInstance<?> classifierInstance, SerializationStatus serializationStatus) {
    Objects.requireNonNull(classifierInstance, "Node should not be null");
    SerializedClassifierInstance serializedClassifierInstance = new SerializedClassifierInstance();
    serializedClassifierInstance.setID(classifierInstance.getID());
    serializedClassifierInstance.setClassifier(
        MetaPointer.from(classifierInstance.getClassifier()));
    if (classifierInstance.getParent() != null) {
      serializedClassifierInstance.setParentNodeID(classifierInstance.getParent().getID());
    }
    serializeProperties(classifierInstance, serializedClassifierInstance, serializationStatus);
    serializeContainments(classifierInstance, serializedClassifierInstance, serializationStatus);
    serializeReferences(
        classifierInstance,
        serializedClassifierInstance,
        builtinsReferenceDangling,
        serializationStatus);
    serializeAnnotations(classifierInstance, serializedClassifierInstance);
    return serializedClassifierInstance;
  }

  private SerializedClassifierInstance serializeAnnotationInstance(
      @Nonnull AnnotationInstance annotationInstance, SerializationStatus serializationStatus) {
    Objects.requireNonNull(annotationInstance, "AnnotationInstance should not be null");
    SerializedClassifierInstance serializedClassifierInstance = new SerializedClassifierInstance();
    serializedClassifierInstance.setID(annotationInstance.getID());
    serializedClassifierInstance.setParentNodeID(annotationInstance.getParent().getID());
    serializedClassifierInstance.setClassifier(
        MetaPointer.from(annotationInstance.getAnnotationDefinition()));
    serializeProperties(annotationInstance, serializedClassifierInstance, serializationStatus);
    serializeContainments(annotationInstance, serializedClassifierInstance, serializationStatus);
    serializeReferences(
        annotationInstance,
        serializedClassifierInstance,
        builtinsReferenceDangling,
        serializationStatus);
    serializeAnnotations(annotationInstance, serializedClassifierInstance);
    return serializedClassifierInstance;
  }

  private static void serializeAnnotations(
      @Nonnull ClassifierInstance<?> classifierInstance,
      SerializedClassifierInstance serializedClassifierInstance) {
    Objects.requireNonNull(classifierInstance, "ClassifierInstance should not be null");
    serializedClassifierInstance.setAnnotations(
        classifierInstance.getAnnotations().stream()
            .map(ClassifierInstance::getID)
            .collect(Collectors.toList()));
  }

  private void serializeReferences(
          @Nonnull ClassifierInstance<?> classifierInstance,
          SerializedClassifierInstance serializedClassifierInstance,
          boolean builtinsReferenceDangling,
          SerializationStatus serializationStatus) {
    Objects.requireNonNull(classifierInstance, "ClassifierInstance should not be null");
    serializationStatus
        .allReferences(classifierInstance.getClassifier())
        .forEach(
            reference -> {
              List<SerializedReferenceValue.Entry> entries =
                  classifierInstance.getReferenceValues(reference).stream()
                      .map(
                          rv -> {
                            String referredID =
                                rv.getReferred() == null ? null : rv.getReferred().getID();
                            if (builtinsReferenceDangling
                                && ClassifierInstanceUtils.isBuiltinElement(rv.getReferred())) {
                              referredID = null;
                            }
                            return new SerializedReferenceValue.Entry(
                                referredID, rv.getResolveInfo());
                          })
                      .collect(Collectors.toList());
              if (serializeEmptyFeatures || !entries.isEmpty()) {
                MetaPointer metaPointer =
                    MetaPointer.from(
                        reference, ((LanguageEntity<?>) reference.getContainer()).getLanguage());
                SerializedReferenceValue referenceValue =
                    new SerializedReferenceValue(metaPointer, entries);
                serializedClassifierInstance.unsafeAppendReferenceValue(referenceValue);
              }
            });
  }

  private void serializeContainments(
          @Nonnull ClassifierInstance<?> classifierInstance,
          SerializedClassifierInstance serializedClassifierInstance,
          SerializationStatus serializationStatus) {
    Objects.requireNonNull(classifierInstance, "ClassifierInstance should not be null");
    serializationStatus
        .allContainments(classifierInstance.getClassifier())
        .forEach(
            containment -> {
              List<String> value =
                  classifierInstance.getChildren(containment).stream()
                      .map(Node::getID)
                      .collect(Collectors.toList());
              // We can avoid serializing empty values
              if (serializeEmptyFeatures || !value.isEmpty()) {
                MetaPointer metaPointer =
                    MetaPointer.from(
                        containment,
                        ((LanguageEntity<?>) containment.getContainer()).getLanguage());
                SerializedContainmentValue containmentValue =
                    new SerializedContainmentValue(metaPointer, value);
                serializedClassifierInstance.unsafeAppendContainmentValue(containmentValue);
              }
            });
  }

  private void serializeProperties(
      ClassifierInstance<?> classifierInstance,
      SerializedClassifierInstance serializedClassifierInstance,
      SerializationStatus serializationStatus) {
    serializationStatus
        .allProperties(classifierInstance.getClassifier())
        .forEach(
            property -> {
              SerializedPropertyValue propertyValue =
                  SerializedPropertyValue.get(
                      MetaPointer.from(
                          property, ((LanguageEntity<?>) property.getContainer()).getLanguage()),
                      serializePropertyValue(
                          property.getType(), classifierInstance.getPropertyValue(property)));
              serializedClassifierInstance.unsafeAppendPropertyValue(propertyValue);
            });
  }

  private String serializePropertyValue(@Nonnull DataType<?> dataType, @Nullable Object value) {
    Objects.requireNonNull(dataType, "cannot serialize property when the dataType is null");
    Objects.requireNonNull(
        dataType.getID(), "cannot serialize property when the dataType.ID is null");
    if (value == null) {
      return null;
    }
    return dataTypesValuesSerialization.serialize(dataType.getID(), value);
  }

  //
  // Deserialization - Protected and Private
  //

  protected void validateSerializationBlock(@Nonnull SerializationChunk serializationBlock) {
    Objects.requireNonNull(serializationBlock, "serializationBlock should not be null");
    if (serializationBlock.getSerializationFormatVersion() == null) {
      throw new IllegalArgumentException("The serializationFormatVersion should not be null");
    }
    if (!serializationBlock
        .getSerializationFormatVersion()
        .equals(lionWebVersion.getVersionString())) {
      throw new IllegalArgumentException(
          "Only serializationFormatVersion supported by this instance of Serialization is '"
              + lionWebVersion.getVersionString()
              + "' but we found '"
              + serializationBlock.getSerializationFormatVersion()
              + "'");
    }
  }

  /**
   * This method returned a sorted version of the original list, so that leaves nodes comes first,
   * or in other words that a parent never precedes its children.
   */
  private DeserializationStatus sortLeavesFirst(List<SerializedClassifierInstance> originalList) {
    DeserializationStatus deserializationStatus =
        new DeserializationStatus(originalList, instanceResolver, dataTypesValuesSerialization);

    // We create the list going from the roots, to their children and so on, and then we will revert
    // the list

    deserializationStatus.putNodesWithNullIDsInFront();

    switch (unavailableParentPolicy) {
      case NULL_REFERENCES:
        {
          // Let's find all the IDs of nodes present here. The nodes with parents not present here
          // are effectively treated as roots and their parent will be set to null, as we cannot
          // retrieve them or set them (until we decide to provide some sort of NodeResolver)
          Set<String> knownIDs =
              originalList.stream()
                  .map(SerializedClassifierInstance::getID)
                  .collect(Collectors.toSet());
          originalList.stream()
              .filter(ci -> !knownIDs.contains(ci.getParentNodeID()))
              .forEach(deserializationStatus::place);
          break;
        }
      case PROXY_NODES:
        {
          // Let's find all the IDs of nodes present here. The nodes with parents not present here
          // are effectively treated as roots and their parent will be set to an instance of a
          // ProxyNode, as we cannot retrieve them or set them (until we decide to provide some
          // sort of NodeResolver)
          Set<String> knownIDs =
              originalList.stream()
                  .map(SerializedClassifierInstance::getID)
                  .collect(Collectors.toSet());
          Set<String> parentIDs =
              originalList.stream()
                  .map(SerializedClassifierInstance::getParentNodeID)
                  .filter(Objects::nonNull)
                  .collect(Collectors.toSet());
          Set<String> unknownParentIDs = Sets.difference(parentIDs, knownIDs);
          originalList.stream()
              .filter(ci -> unknownParentIDs.contains(ci.getParentNodeID()))
              .forEach(deserializationStatus::place);

          unknownParentIDs.forEach(deserializationStatus::createProxy);
          break;
        }
    }

    // We can start by putting at the start all the elements which either have no parent,
    // or had a parent already added to the list
    while (deserializationStatus.howManySorted() < originalList.size()) {
      int initialLength = deserializationStatus.howManySorted();
      for (int i = 0; i < deserializationStatus.howManyToSort(); i++) {
        SerializedClassifierInstance node = deserializationStatus.getNodeToSort(i);

        boolean parentIsNullOrSorted =
            node.getParentNodeID() == null
                || deserializationStatus.isSortedID(node.getParentNodeID());
        boolean parentIsNotNeeded =
            unavailableParentPolicy == UnavailableNodePolicy.NULL_REFERENCES
                || unavailableParentPolicy == UnavailableNodePolicy.PROXY_NODES;

        if (parentIsNullOrSorted || parentIsNotNeeded) {
          deserializationStatus.place(node);
          i--;
        }
      }
      if (initialLength == deserializationStatus.howManySorted()) {
        if (deserializationStatus.howManySorted() == 0
            && unavailableParentPolicy == UnavailableNodePolicy.THROW_ERROR) {
          throw new DeserializationException(
              "No root found and parents cannot be proxied or set to null, so we cannot deserialize this tree. Original list: "
                  + originalList);
        } else {
          throw new DeserializationException(
              "Something is not right: we are unable to complete sorting the list "
                  + originalList
                  + ". Probably there is a containment loop");
        }
      }
    }

    deserializationStatus.reverse();
    return deserializationStatus;
  }

  public List<ClassifierInstance<?>> deserializeSerializationChunk(
      SerializationChunk serializationBlock) {
    return deserializeClassifierInstances(
        LionWebVersion.fromValue(serializationBlock.getSerializationFormatVersion()),
        serializationBlock.getClassifierInstances());
  }

  private List<ClassifierInstance<?>> deserializeClassifierInstances(
      @Nonnull LionWebVersion lionWebVersion,
      List<SerializedClassifierInstance> serializedClassifierInstances) {
    Objects.requireNonNull(lionWebVersion, "lionWebVersion should not be null");
    // We want to deserialize the nodes starting from the leaves. This is useful because in certain
    // cases we may want to use the children as constructor parameters of the parent
    DeserializationStatus deserializationStatus = sortLeavesFirst(serializedClassifierInstances);
    List<SerializedClassifierInstance> sortedSerializedClassifierInstances =
        deserializationStatus.getSortedList();
    if (sortedSerializedClassifierInstances.size() != serializedClassifierInstances.size()) {
      throw new IllegalStateException();
    }
    Map<String, ClassifierInstance<?>> deserializedByID = new HashMap<>();
    IdentityHashMap<SerializedClassifierInstance, ClassifierInstance<?>> serializedToInstanceMap =
        new IdentityHashMap<>();
    sortedSerializedClassifierInstances.forEach(
        n -> {
          ClassifierInstance<?> instantiated =
              instantiateFromSerialized(lionWebVersion, deserializationStatus, n, deserializedByID);
          if (n.getID() != null && deserializedByID.containsKey(n.getID())) {
            throw new IllegalStateException("Duplicate ID found: " + n.getID());
          }
          deserializedByID.put(n.getID(), instantiated);
          serializedToInstanceMap.put(n, instantiated);
        });
    if (sortedSerializedClassifierInstances.size() != serializedToInstanceMap.size()) {
      throw new IllegalStateException(
          "We got "
              + sortedSerializedClassifierInstances.size()
              + " nodes to deserialize, but we deserialized "
              + serializedToInstanceMap.size());
    }
    ClassifierInstanceResolver classifierInstanceResolver =
        new CompositeClassifierInstanceResolver(
            new MapBasedResolver(deserializedByID),
            deserializationStatus.getProxiesInstanceResolver(),
            this.instanceResolver);
    NodePopulator nodePopulator =
        new NodePopulator(this, classifierInstanceResolver, deserializationStatus, lionWebVersion);
    serializedClassifierInstances.forEach(
        node -> {
          ClassifierInstance<?> classifierInstance = serializedToInstanceMap.get(node);
          nodePopulator.populateClassifierInstance(classifierInstance, node);
          ClassifierInstance<?> parent = classifierInstanceResolver.resolve(node.getParentNodeID());
          if (parent instanceof ProxyNode
              && unavailableParentPolicy == UnavailableNodePolicy.PROXY_NODES) {
            // For real parents, the parent is not set directly, but it is set indirectly
            // when adding the child to the parent. For proxy nodes instead we need to set
            // the parent explicitly
            ProxyNode proxyParent = (ProxyNode) parent;
            if (classifierInstance instanceof HasSettableParent) {
              ((HasSettableParent) classifierInstance).setParent(proxyParent);
            } else {
              throw new UnsupportedOperationException(
                  "We do not know how to set explicitly the parent of " + classifierInstance);
            }
          }
          if (classifierInstance instanceof AnnotationInstance) {
            AbstractClassifierInstance<?> abstractClassifierInstance =
                (AbstractClassifierInstance<?>) deserializedByID.get(node.getParentNodeID());
            AnnotationInstance annotationInstance = (AnnotationInstance) classifierInstance;
            if (abstractClassifierInstance != null) {
              abstractClassifierInstance.addAnnotation(annotationInstance);
            } else {
              throw new IllegalStateException(
                  "Cannot resolved annotated node " + annotationInstance.getParent());
            }
          }
        });

    // We want the nodes returned to be sorted as the original serializedNodes
    List<ClassifierInstance<?>> nodesWithOriginalSorting =
        serializedClassifierInstances.stream()
            .map(serializedToInstanceMap::get)
            .collect(Collectors.toList());
    nodesWithOriginalSorting.addAll(deserializationStatus.proxies);
    return nodesWithOriginalSorting;
  }

  private ClassifierInstance<?> instantiateFromSerialized(
      @Nonnull LionWebVersion lionWebVersion,
      DeserializationStatus deserializationStatus,
      SerializedClassifierInstance serializedClassifierInstance,
      Map<String, ClassifierInstance<?>> deserializedByID) {
    Objects.requireNonNull(lionWebVersion, "lionWebVersion should not be null");
    MetaPointer serializedClassifier = serializedClassifierInstance.getClassifier();
    if (serializedClassifier == null) {
      throw new RuntimeException("No metaPointer available for " + serializedClassifierInstance);
    }
    Classifier<?> classifier = getClassifierResolver().resolveClassifier(serializedClassifier);

    // We prepare all the properties values and pass them to instantiator, as it could use them to
    // build the node
    Map<Property, Object> propertiesValues = new HashMap<>();
    serializedClassifierInstance
        .getProperties()
        .forEach(
            serializedPropertyValue -> {
              Property property =
                  deserializationStatus.getProperty(
                      classifier, serializedPropertyValue.getMetaPointer());
              if (property == null) {
                throw new NullPointerException(
                    "Property with metaPointer "
                        + serializedPropertyValue.getMetaPointer()
                        + " not found in classifier "
                        + classifier
                        + ". Properties: "
                        + classifier.allProperties().stream()
                            .map(MetaPointer::from)
                            .collect(Collectors.toList()));
              }
              Objects.requireNonNull(property.getType(), "property type should not be null");
              Object deserializedValue =
                  deserializationStatus.deserializePropertyValue(
                      property.getType(),
                      serializedPropertyValue.getValue(),
                      property.isRequired());
              propertiesValues.put(property, deserializedValue);
            });
    ClassifierInstance<?> classifierInstance =
        getInstantiator()
            .instantiate(
                classifier, serializedClassifierInstance, deserializedByID, propertiesValues);

    // We ensure that the properties values are set correctly. They could already have been set
    // while instantiating the node. If that is the case, we have nothing to do, otherwise we set
    // the values
    propertiesValues.forEach(
        (property, deserializedValue) -> {
          // Avoiding calling setters, in case the value has been already set at construction
          // time

          if (!Objects.equals(deserializedValue, classifierInstance.getPropertyValue(property))) {
            classifierInstance.setPropertyValue(property, deserializedValue);
          }
        });

    return classifierInstance;
  }

  public LionWebVersion getLionWebVersion() {
    return lionWebVersion;
  }
}
