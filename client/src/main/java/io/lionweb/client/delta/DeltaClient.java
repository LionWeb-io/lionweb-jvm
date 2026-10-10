package io.lionweb.client.delta;

import io.lionweb.LionWebVersion;
import io.lionweb.client.delta.messages.BaseDeltaEvent;
import io.lionweb.client.delta.messages.DeltaEvent;
import io.lionweb.client.delta.messages.DeltaQueryResponse;
import io.lionweb.client.delta.messages.commands.ChangeClassifier;
import io.lionweb.client.delta.messages.commands.annotations.AddAnnotation;
import io.lionweb.client.delta.messages.commands.annotations.DeleteAnnotation;
import io.lionweb.client.delta.messages.commands.annotations.MoveAnnotationFromOtherParent;
import io.lionweb.client.delta.messages.commands.annotations.MoveAnnotationInSameParent;
import io.lionweb.client.delta.messages.commands.children.*;
import io.lionweb.client.delta.messages.commands.partitions.AddPartition;
import io.lionweb.client.delta.messages.commands.partitions.DeletePartition;
import io.lionweb.client.delta.messages.commands.properties.AddProperty;
import io.lionweb.client.delta.messages.commands.properties.ChangeProperty;
import io.lionweb.client.delta.messages.commands.properties.DeleteProperty;
import io.lionweb.client.delta.messages.commands.references.AddReference;
import io.lionweb.client.delta.messages.commands.references.ChangeReference;
import io.lionweb.client.delta.messages.commands.references.DeleteReference;
import io.lionweb.client.delta.messages.events.ClassifierChanged;
import io.lionweb.client.delta.messages.events.ErrorEvent;
import io.lionweb.client.delta.messages.events.annotations.AnnotationAdded;
import io.lionweb.client.delta.messages.events.annotations.AnnotationDeleted;
import io.lionweb.client.delta.messages.events.annotations.AnnotationMovedFromOtherParent;
import io.lionweb.client.delta.messages.events.annotations.AnnotationMovedInSameParent;
import io.lionweb.client.delta.messages.events.children.*;
import io.lionweb.client.delta.messages.events.partitions.PartitionAdded;
import io.lionweb.client.delta.messages.events.partitions.PartitionDeleted;
import io.lionweb.client.delta.messages.events.properties.PropertyAdded;
import io.lionweb.client.delta.messages.events.properties.PropertyChanged;
import io.lionweb.client.delta.messages.events.properties.PropertyDeleted;
import io.lionweb.client.delta.messages.events.references.ReferenceAdded;
import io.lionweb.client.delta.messages.events.references.ReferenceChanged;
import io.lionweb.client.delta.messages.events.references.ReferenceDeleted;
import io.lionweb.client.delta.messages.queries.ErrorResponse;
import io.lionweb.client.delta.messages.queries.ListAndSubscribePartitionsRequest;
import io.lionweb.client.delta.messages.queries.ListAndSubscribePartitionsResponse;
import io.lionweb.client.delta.messages.queries.ListPartitionsRequest;
import io.lionweb.client.delta.messages.queries.ListPartitionsResponse;
import io.lionweb.client.delta.messages.queries.participations.*;
import io.lionweb.client.delta.messages.queries.subscriptions.SubscribeToPartitionContentsRequest;
import io.lionweb.client.delta.messages.queries.subscriptions.SubscribeToPartitionContentsResponse;
import io.lionweb.client.delta.messages.queries.subscriptions.UnsubscribeFromPartitionContentsRequest;
import io.lionweb.client.delta.messages.queries.subscriptions.UnsubscribeFromPartitionContentsResponse;
import io.lionweb.language.Containment;
import io.lionweb.language.Language;
import io.lionweb.language.Property;
import io.lionweb.language.Reference;
import io.lionweb.model.*;
import io.lionweb.model.impl.ProxyNode;
import io.lionweb.serialization.AbstractSerialization;
import io.lionweb.serialization.DataTypesValuesSerialization;
import io.lionweb.serialization.SerializationProvider;
import io.lionweb.serialization.UnavailableNodePolicy;
import io.lionweb.serialization.data.MetaPointer;
import io.lionweb.serialization.data.SerializationChunk;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * This Client processes communications received through a DeltaChannel and monitor nodes. Based on
 * the events received, it will update the local nodes. Based on the changes observed on the local
 * nodes, it will send the changes through the DeltaChannel.
 */
public class DeltaClient implements DeltaEventReceiver, DeltaQueryResponseReceiver {
  private final @NotNull DeltaChannel channel;
  private final @NotNull MonitoringObserver observer = new MonitoringObserver();
  private final @NotNull HashMap<String, Set<WeakReference<ClassifierInstance<?>>>> nodes =
      new HashMap<>();
  private final @NotNull DataTypesValuesSerialization dataTypesValuesSerialization =
      new DataTypesValuesSerialization();
  private final @NotNull AbstractSerialization serialization;
  private final @NotNull Set<String> queriesSent = new HashSet<>();
  private final @NotNull String clientId;
  private final @NotNull LionWebVersion lionWebVersion;
  private @Nullable String participationId;
  private @Nullable String pendingReconnectParticipationId;
  private final @NotNull Set<String> monitoredPartitions = new LinkedHashSet<>();
  private @NotNull ParticipationState state = ParticipationState.NOT_CONNECTED;

  public DeltaClient(@NotNull DeltaChannel channel, @NotNull String clientId) {
    this(LionWebVersion.currentVersion, channel, clientId);
  }

  public DeltaClient(
      @NotNull LionWebVersion lionWebVersion,
      @NotNull DeltaChannel channel,
      @NotNull String clientId) {
    Objects.requireNonNull(lionWebVersion, "lionWebVersion must not be null");
    Objects.requireNonNull(channel, "channel must not be null");
    Objects.requireNonNull(clientId, "clientId must not be null");

    this.clientId = clientId;
    this.lionWebVersion = lionWebVersion;
    this.channel = channel;
    this.channel.registerEventReceiver(this);
    this.channel.registerQueryResponseReceiver(this);
    this.dataTypesValuesSerialization.registerLionBuiltinsPrimitiveSerializersAndDeserializers(
        lionWebVersion);
    this.serialization = SerializationProvider.getStandardJsonSerialization(lionWebVersion);
    this.serialization.setUnavailableParentPolicy(UnavailableNodePolicy.PROXY_NODES);
    this.serialization.setUnavailableReferenceTargetPolicy(UnavailableNodePolicy.PROXY_NODES);
    this.serialization.enableDynamicNodes();
  }

  /**
   * It is the responsibility of the caller to ensure that the partition is initially in sync with
   * the server.
   *
   * <p>The client subscribes to the contents of the partition, so that it receives the events
   * concerning it. If the client is not yet connected, the subscription is performed upon sign-on.
   */
  public void monitorPartition(@NotNull Node partition) {
    Objects.requireNonNull(partition, "partition should not be null");
    synchronized (partition) {
      partition
          .thisAndAllDescendants()
          .forEach(
              n ->
                  nodes
                      .computeIfAbsent(n.getID(), id -> new HashSet<>())
                      .add(new WeakReference<>(n)));
      partition.registerPartitionObserver(observer);
    }
    if (monitoredPartitions.add(partition.getID()) && state == ParticipationState.CONNECTED) {
      subscribeToMonitoredPartition(partition.getID());
    }
  }

  /**
   * Subscribes to a monitored partition. If the partition is not (yet) known to the repository the
   * subscription is rejected: this is tolerated, as the partition may be created later through
   * {@link #sendAddPartitionCommand(Node)}, which subscribes the creating participation to it.
   */
  private void subscribeToMonitoredPartition(@NotNull String partitionId) {
    channel.sendQuery(
        queryId -> {
          queriesSent.add(queryId);
          return new SubscribeToPartitionContentsRequest(queryId, partitionId);
        });
  }

  @Override
  public void receiveEvent(@NotNull DeltaEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    if (isFromOwnParticipation(event)) return;
    observer.paused = true;
    try {
      if (event instanceof PropertyChanged) onPropertyChanged((PropertyChanged) event);
      else if (event instanceof PropertyAdded) onPropertyAdded((PropertyAdded) event);
      else if (event instanceof PropertyDeleted) onPropertyDeleted((PropertyDeleted) event);
      else if (event instanceof ChildAdded) onChildAdded((ChildAdded) event);
      else if (event instanceof ChildDeleted) onChildDeleted((ChildDeleted) event);
      else if (event instanceof ReferenceAdded) onReferenceAdded((ReferenceAdded) event);
      else if (event instanceof ReferenceChanged) onReferenceChanged((ReferenceChanged) event);
      else if (event instanceof ReferenceDeleted) onReferenceDeleted((ReferenceDeleted) event);
      else if (event instanceof ChildMovedInSameContainment)
        onChildMovedInSameContainment((ChildMovedInSameContainment) event);
      else if (event instanceof ChildMovedFromOtherContainmentInSameParent)
        onChildMovedFromOtherContainmentInSameParent(
            (ChildMovedFromOtherContainmentInSameParent) event);
      else if (event instanceof ChildMovedFromOtherContainment)
        onChildMovedFromOtherContainment((ChildMovedFromOtherContainment) event);
      else if (event instanceof ChildReplaced) onChildReplaced((ChildReplaced) event);
      else if (event instanceof ClassifierChanged) onClassifierChanged((ClassifierChanged) event);
      else if (event instanceof AnnotationAdded) onAnnotationAdded((AnnotationAdded) event);
      else if (event instanceof AnnotationDeleted) onAnnotationDeleted((AnnotationDeleted) event);
      else if (event instanceof AnnotationMovedInSameParent
          || event instanceof AnnotationMovedFromOtherParent) {
        throw new UnsupportedOperationException();
      } else if (event instanceof ErrorEvent) onErrorEvent((ErrorEvent) event);
      else if (event instanceof PartitionAdded || event instanceof PartitionDeleted) {
        /* no-op */
      } else
        throw new UnsupportedOperationException(
            "Unsupported event type: " + event.getClass().getName());
    } finally {
      observer.paused = false;
    }
  }

  /**
   * Retrieves the participation ID associated with the current session.
   *
   * @return the participation ID if available, or {@code null} if no participation ID has been set
   *     or the client is not currently participating.
   */
  @Override
  public @Nullable String getParticipationId() {
    return participationId;
  }

  public @NotNull ParticipationState getState() {
    return state;
  }

  @Override
  public void receiveQueryResponse(@NotNull DeltaQueryResponse queryResponse) {
    Objects.requireNonNull(queryResponse, "queryResponse must not be null");
    if (!queriesSent.contains(queryResponse.queryId)) return;
    if (queryResponse instanceof SignOnResponse) {
      SignOnResponse signOnResponse = (SignOnResponse) queryResponse;
      this.participationId = signOnResponse.participationId;
      this.state = ParticipationState.CONNECTED;
      return;
    } else if (queryResponse instanceof SignOffResponse) {
      this.state = ParticipationState.SIGNED_OFF;
      return;
    } else if (queryResponse instanceof ReconnectResponse) {
      this.participationId = pendingReconnectParticipationId;
      this.pendingReconnectParticipationId = null;
      this.state = ParticipationState.CONNECTED;
      return;
    } else if (queryResponse instanceof ListPartitionsResponse
        || queryResponse instanceof ListAndSubscribePartitionsResponse
        || queryResponse instanceof SubscribeToPartitionContentsResponse
        || queryResponse instanceof UnsubscribeFromPartitionContentsResponse
        || queryResponse instanceof ErrorResponse) {
      return; // Callers receive these via the return value of the send methods
    }
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void sendSignOnRequest() {
    channel.sendQuery(
        queryId -> {
          queriesSent.add(queryId);
          return new SignOnRequest(queryId, DeltaProtocolVersion.v2025_1, clientId);
        });
    if (state == ParticipationState.CONNECTED) {
      monitoredPartitions.forEach(this::subscribeToMonitoredPartition);
    }
  }

  /** Sends a SignOffRequest to terminate the current participation. */
  public void sendSignOffRequest() {
    channel.sendQuery(
        queryId -> {
          queriesSent.add(queryId);
          return new SignOffRequest(queryId);
        });
  }

  /**
   * Resumes a prior participation after a transport-level disconnect.
   *
   * @param existingParticipationId the participationId from the previous session
   * @param lastReceivedSequenceNumber the sequence number of the last event the client received
   */
  public void sendReconnectRequest(
      @NotNull String existingParticipationId, long lastReceivedSequenceNumber) {
    Objects.requireNonNull(existingParticipationId, "existingParticipationId must not be null");
    this.pendingReconnectParticipationId = existingParticipationId;
    DeltaQueryResponse response =
        channel.sendQuery(
            queryId -> {
              queriesSent.add(queryId);
              return new ReconnectRequest(
                  queryId, existingParticipationId, lastReceivedSequenceNumber);
            });
    if (response instanceof ErrorResponse) {
      this.pendingReconnectParticipationId = null;
      ErrorResponse error = (ErrorResponse) response;
      throw new ErrorEventReceivedException(error.errorCode, error.message);
    }
  }

  /** Requests the list of partitions currently held in the repository. */
  public @NotNull ListPartitionsResponse sendListPartitionsRequest() {
    return (ListPartitionsResponse)
        channel.sendQuery(
            queryId -> {
              queriesSent.add(queryId);
              return new ListPartitionsRequest(queryId);
            });
  }

  /**
   * Requests the list of partitions and registers the participation for future partition-lifecycle
   * events.
   */
  public @NotNull ListAndSubscribePartitionsResponse sendListAndSubscribePartitionsRequest() {
    return (ListAndSubscribePartitionsResponse)
        channel.sendQuery(
            queryId -> {
              queriesSent.add(queryId);
              return new ListAndSubscribePartitionsRequest(queryId);
            });
  }

  /**
   * Subscribes to a specific partition, returning its current contents.
   *
   * @param partitionId node id of the partition to subscribe to
   */
  public @NotNull SubscribeToPartitionContentsResponse sendSubscribeToPartitionContentsRequest(
      @NotNull String partitionId) {
    Objects.requireNonNull(partitionId, "partitionId must not be null");
    return (SubscribeToPartitionContentsResponse)
        channel.sendQuery(
            queryId -> {
              queriesSent.add(queryId);
              return new SubscribeToPartitionContentsRequest(queryId, partitionId);
            });
  }

  /**
   * Unsubscribes from a previously subscribed partition.
   *
   * @param partitionId node id of the partition to unsubscribe from
   */
  public @NotNull UnsubscribeFromPartitionContentsResponse
      sendUnsubscribeFromPartitionContentsRequest(@NotNull String partitionId) {
    Objects.requireNonNull(partitionId, "partitionId must not be null");
    return (UnsubscribeFromPartitionContentsResponse)
        channel.sendQuery(
            queryId -> {
              queriesSent.add(queryId);
              return new UnsubscribeFromPartitionContentsRequest(queryId, partitionId);
            });
  }

  /**
   * Sends a command to create a new partition in the repository.
   *
   * @param partition the root node of the partition to add
   */
  public void sendAddPartitionCommand(@NotNull Node partition) {
    Objects.requireNonNull(partition, "partition must not be null");
    SerializationChunk chunk = serialization.serializeNodesToSerializationChunk(partition);
    channel.sendCommand(ensureParticipation(), commandId -> new AddPartition(commandId, chunk));
  }

  /**
   * Sends a command to delete an existing partition from the repository.
   *
   * @param partitionId node id of the partition to delete
   */
  public void sendDeletePartitionCommand(@NotNull String partitionId) {
    Objects.requireNonNull(partitionId, "partitionId must not be null");
    channel.sendCommand(
        ensureParticipation(), commandId -> new DeletePartition(commandId, partitionId));
  }

  /**
   * Sends a command to change the classifier of a node.
   *
   * @param nodeId id of the node whose classifier should be replaced
   * @param newClassifier the new classifier MetaPointer
   */
  public void sendChangeClassifierCommand(
      @NotNull String nodeId, @NotNull MetaPointer newClassifier) {
    Objects.requireNonNull(nodeId, "nodeId must not be null");
    Objects.requireNonNull(newClassifier, "newClassifier must not be null");
    channel.sendCommand(
        ensureParticipation(), commandId -> new ChangeClassifier(commandId, nodeId, newClassifier));
  }

  /**
   * Moves a child within its current containment from {@code oldIndex} to {@code newIndex}.
   *
   * @param parent id of the parent node
   * @param containment MetaPointer of the containment feature
   * @param movedChild id of the child to move
   * @param oldIndex current 0-based position of the child
   * @param newIndex desired final 0-based position after the move
   */
  public void sendMoveChildInSameContainmentCommand(
      @NotNull String parent,
      @NotNull MetaPointer containment,
      @NotNull String movedChild,
      int oldIndex,
      int newIndex) {
    Objects.requireNonNull(parent, "parent must not be null");
    Objects.requireNonNull(containment, "containment must not be null");
    Objects.requireNonNull(movedChild, "movedChild must not be null");
    channel.sendCommand(
        ensureParticipation(),
        commandId ->
            new MoveChildInSameContainment(
                commandId, newIndex - oldIndex, movedChild, parent, containment, oldIndex));
  }

  /**
   * Moves a child from one parent/containment to a different parent/containment.
   *
   * @param oldParent id of the old parent node
   * @param oldContainment MetaPointer of the old containment feature
   * @param oldIndex current position in the old containment
   * @param newParent id of the new parent node
   * @param newContainment MetaPointer of the new containment feature
   * @param newIndex desired final position in the new containment
   * @param movedChild id of the child to move
   */
  public void sendMoveChildFromOtherContainmentCommand(
      @NotNull String oldParent,
      @NotNull MetaPointer oldContainment,
      int oldIndex,
      @NotNull String newParent,
      @NotNull MetaPointer newContainment,
      int newIndex,
      @NotNull String movedChild) {
    Objects.requireNonNull(oldParent, "oldParent must not be null");
    Objects.requireNonNull(oldContainment, "oldContainment must not be null");
    Objects.requireNonNull(newParent, "newParent must not be null");
    Objects.requireNonNull(newContainment, "newContainment must not be null");
    Objects.requireNonNull(movedChild, "movedChild must not be null");
    channel.sendCommand(
        ensureParticipation(),
        commandId ->
            new MoveChildFromOtherContainment(
                commandId,
                newParent,
                newContainment,
                newIndex,
                oldParent,
                oldContainment,
                oldIndex,
                movedChild));
  }

  /**
   * Moves a child between two containments of the same parent.
   *
   * @param parent id of the parent node
   * @param oldContainment MetaPointer of the source containment feature
   * @param oldIndex current position in the old containment
   * @param newContainment MetaPointer of the target containment feature
   * @param newIndex desired final position in the new containment
   * @param movedChild id of the child to move
   */
  public void sendMoveChildFromOtherContainmentInSameParentCommand(
      @NotNull String parent,
      @NotNull MetaPointer oldContainment,
      int oldIndex,
      @NotNull MetaPointer newContainment,
      int newIndex,
      @NotNull String movedChild) {
    Objects.requireNonNull(parent, "parent must not be null");
    Objects.requireNonNull(oldContainment, "oldContainment must not be null");
    Objects.requireNonNull(newContainment, "newContainment must not be null");
    Objects.requireNonNull(movedChild, "movedChild must not be null");
    channel.sendCommand(
        ensureParticipation(),
        commandId ->
            new MoveChildFromOtherContainmentInSameParent(
                commandId, newContainment, newIndex, movedChild, parent, oldContainment, oldIndex));
  }

  /**
   * Replaces an existing child node with a new one at the same index.
   *
   * @param parent id of the parent node
   * @param containment MetaPointer of the containment feature
   * @param index position of the child to replace
   * @param replacedChild id of the child node being removed
   * @param newChild the new child node tree to insert
   */
  public void sendReplaceChildCommand(
      @NotNull String parent,
      @NotNull MetaPointer containment,
      int index,
      @NotNull String replacedChild,
      @NotNull Node newChild) {
    Objects.requireNonNull(parent, "parent must not be null");
    Objects.requireNonNull(containment, "containment must not be null");
    Objects.requireNonNull(replacedChild, "replacedChild must not be null");
    Objects.requireNonNull(newChild, "newChild must not be null");
    SerializationChunk chunk = serialization.serializeNodesToSerializationChunk(newChild);
    channel.sendCommand(
        ensureParticipation(),
        commandId -> new ReplaceChild(commandId, chunk, parent, containment, index, replacedChild));
  }

  /**
   * Sends a command to add a new child node to a parent in the given containment at the given
   * index.
   *
   * @param parentId id of the parent node
   * @param containment MetaPointer of the containment feature
   * @param child the new child node to add
   * @param index 0-based position at which to insert the child
   */
  public void sendAddChildCommand(
      @NotNull String parentId, @NotNull MetaPointer containment, @NotNull Node child, int index) {
    Objects.requireNonNull(parentId, "parentId must not be null");
    Objects.requireNonNull(containment, "containment must not be null");
    Objects.requireNonNull(child, "child must not be null");
    SerializationChunk chunk = serialization.serializeNodesToSerializationChunk(child);
    // The server validates that any node with parentNodeID=null is a registered partition.
    // For AddChild, the root of the subtree being added must have its parentNodeID set to the
    // parent so the server can place it correctly in the tree.
    SerializedClassifierInstance rootInst = chunk.getClassifierInstancesByID().get(child.getID());
    if (rootInst != null && rootInst.getParentNodeID() == null) {
      rootInst.setParentNodeID(parentId);
    }
    channel.sendCommand(
        ensureParticipation(),
        commandId -> new AddChild(commandId, parentId, chunk, containment, index));
  }

  /**
   * Sends a command to delete a child node from a parent's containment.
   *
   * @param parentId id of the parent node
   * @param containment MetaPointer of the containment feature
   * @param index 0-based position of the child to delete
   * @param childId id of the child node to delete
   */
  public void sendDeleteChildCommand(
      @NotNull String parentId,
      @NotNull MetaPointer containment,
      int index,
      @NotNull String childId) {
    Objects.requireNonNull(parentId, "parentId must not be null");
    Objects.requireNonNull(containment, "containment must not be null");
    Objects.requireNonNull(childId, "childId must not be null");
    channel.sendCommand(
        ensureParticipation(),
        commandId -> new DeleteChild(commandId, parentId, containment, index, childId));
  }

  /**
   * Sends a command to set a property value on a node. Uses AddProperty if the property is not yet
   * set, ChangeProperty otherwise.
   *
   * @param nodeId id of the node
   * @param property MetaPointer of the property feature
   * @param newValue the new value (as serialized string), or null to unset
   * @param propertyAlreadySet true if the property already has a value (sends ChangeProperty),
   *     false if it is unset (sends AddProperty)
   */
  public void sendSetPropertyCommand(
      @NotNull String nodeId,
      @NotNull MetaPointer property,
      @Nullable String newValue,
      boolean propertyAlreadySet) {
    Objects.requireNonNull(nodeId, "nodeId must not be null");
    Objects.requireNonNull(property, "property must not be null");
    if (propertyAlreadySet) {
      channel.sendCommand(
          ensureParticipation(),
          commandId -> new ChangeProperty(commandId, nodeId, property, newValue));
    } else {
      channel.sendCommand(
          ensureParticipation(),
          commandId -> new AddProperty(commandId, nodeId, property, newValue));
    }
  }

  /**
   * Registers a language in this client's serialization context so that nodes and annotations of
   * that language can be properly deserialized from incoming events.
   *
   * @param language the language to register
   */
  public void registerLanguage(@NotNull Language language) {
    Objects.requireNonNull(language, "language must not be null");
    this.serialization.registerLanguage(language);
  }

  /**
   * Sends a command to add an annotation to a node at a given index.
   *
   * @param parentId node id of the node to annotate
   * @param annotation the annotation instance to add
   * @param index 0-based position in the parent's annotation list
   */
  public void sendAddAnnotationCommand(
      @NotNull String parentId, @NotNull AnnotationInstance annotation, int index) {
    SerializationChunk chunk = serialization.serializeTreeToSerializationChunk(annotation);
    channel.sendCommand(
        ensureParticipation(), commandId -> new AddAnnotation(commandId, parentId, chunk, index));
  }

  /**
   * Sends a command to delete an annotation from a node.
   *
   * @param parentId node id of the annotated node
   * @param index 0-based position of the annotation to remove
   * @param annotationId id of the annotation instance to remove
   */
  public void sendDeleteAnnotationCommand(
      @NotNull String parentId, int index, @NotNull String annotationId) {
    channel.sendCommand(
        ensureParticipation(),
        commandId -> new DeleteAnnotation(commandId, parentId, index, annotationId));
  }

  /**
   * Sends a command to move an annotation within the same parent's annotation list.
   *
   * @param parentId node id of the annotated node
   * @param movedAnnotationId id of the annotation to move
   * @param oldIndex current 0-based position
   * @param newIndex desired final 0-based position
   */
  public void sendMoveAnnotationInSameParentCommand(
      @NotNull String parentId, @NotNull String movedAnnotationId, int oldIndex, int newIndex) {
    channel.sendCommand(
        ensureParticipation(),
        commandId ->
            new MoveAnnotationInSameParent(
                commandId, newIndex - oldIndex, movedAnnotationId, parentId, oldIndex));
  }

  /**
   * Sends a command to move an annotation from one node to another.
   *
   * @param oldParentId node id of the current annotated node
   * @param newParentId node id of the target annotated node
   * @param movedAnnotationId id of the annotation to move
   * @param oldIndex current 0-based position in the old parent
   * @param newIndex desired final 0-based position in the new parent
   */
  public void sendMoveAnnotationFromOtherParentCommand(
      @NotNull String oldParentId,
      @NotNull String newParentId,
      @NotNull String movedAnnotationId,
      int oldIndex,
      int newIndex) {
    channel.sendCommand(
        ensureParticipation(),
        commandId ->
            new MoveAnnotationFromOtherParent(
                commandId, newParentId, newIndex, movedAnnotationId, oldParentId, oldIndex));
  }

  protected void monitorNode(@NotNull Node node) {
    Objects.requireNonNull(node, "node must not be null");
    nodes.computeIfAbsent(node.getID(), id -> new HashSet<>()).add(new WeakReference<>(node));
  }

  /**
   * Ensures a SerializationChunk received via the delta wire format has its
   * serializationFormatVersion set before passing it to the standard serialization layer. The delta
   * protocol omits this field, so we inject the version this client was configured with.
   */
  private SerializationChunk withSerializationFormatVersion(@NotNull SerializationChunk chunk) {
    if (chunk.getSerializationFormatVersion() == null) {
      chunk.setSerializationFormatVersion(lionWebVersion.getVersionString());
    } else if (!chunk.getSerializationFormatVersion().equals(lionWebVersion.getVersionString())) {
      throw new IllegalStateException(
          "Received chunk with incompatible serialization format version: "
              + chunk.getSerializationFormatVersion()
              + ", expected: "
              + lionWebVersion.getVersionString());
    }
    return chunk;
  }

  private boolean isFromOwnParticipation(@NotNull DeltaEvent event) {
    if (!(event instanceof BaseDeltaEvent)) return false;
    return ((BaseDeltaEvent<?>) event)
        .originCommands.stream().anyMatch(src -> src.participationId.equals(this.participationId));
  }

  private void onPropertyChanged(@NotNull PropertyChanged event) {
    forEachNode(
        event.node,
        instance ->
            ClassifierInstanceUtils.setPropertyValueByMetaPointer(
                instance, event.property, event.newValue));
  }

  /**
   * We are processing an event affecting a certain node. If the node is not relevant for us
   * (because we are not monitoring it), we can ignore it.
   *
   * @param nodeId the id of the node that was affected by the event
   */
  private void processRelevantNode(
      @Nullable String nodeId, Consumer<ClassifierInstance<?>> nodeOperation) {
    if (nodeId == null) return;
    Set<WeakReference<ClassifierInstance<?>>> refs = nodes.get(nodeId);
    if (refs == null) return;
    for (WeakReference<ClassifierInstance<?>> ref : refs) {
      ClassifierInstance<?> instance = ref.get();
      if (instance == null) return;
      nodeOperation.accept(instance);
    }
  }

  private @NotNull String ensureParticipation() {
    if (participationId == null) {
      throw new IllegalStateException("Participation not set");
    }
    return this.participationId;
  }

  private void onChildAdded(@NotNull ChildAdded event) {
    processRelevantNode(
        event.parent,
        instance -> {
          Node child =
              (Node)
                  serialization
                      .deserializeSerializationChunk(withSerializationFormatVersion(event.newChild))
                      .get(0);
          monitorNode(child);
          Containment containment =
              instance.getClassifier().getContainmentByMetaPointer(event.containment);
          if (containment == null) {
            throw new IllegalStateException(
                "Containment not found for "
                    + instance
                    + " using metapointer "
                    + event.containment);
          }
          instance.addChild(containment, child, event.index);
        });
  }

  private void onChildDeleted(@NotNull ChildDeleted event) {
    processRelevantNode(
        event.parent,
        instance -> {
          Containment containment =
              instance.getClassifier().getContainmentByMetaPointer(event.containment);
          if (containment == null) {
            throw new IllegalStateException(
                "Containment not found for "
                    + instance
                    + " using metapointer "
                    + event.containment);
          }
          instance.removeChild(containment, event.index);
        });
  }

  private void onReferenceAdded(@NotNull ReferenceAdded event) {
    processRelevantNode(
        event.parent,
        instance -> {
          Reference reference = instance.getClassifier().getReferenceByMetaPointer(event.reference);
          if (reference == null) {
            throw new IllegalStateException(
                "Reference not found for " + instance + " using metapointer " + event.reference);
          }
          instance.addReferenceValue(
              reference,
              event.index,
              new ReferenceValue(new ProxyNode(event.newReference), event.newResolveInfo));
        });
  }

  private void onPropertyAdded(@NotNull PropertyAdded event) {
    forEachNode(
        event.node,
        instance ->
            ClassifierInstanceUtils.setPropertyValueByMetaPointer(
                instance, event.property, event.newValue));
  }

  private void onPropertyDeleted(@NotNull PropertyDeleted event) {
    forEachNode(
        event.node,
        instance ->
            ClassifierInstanceUtils.setPropertyValueByMetaPointer(instance, event.property, null));
  }

  private void onReferenceChanged(@NotNull ReferenceChanged event) {
    forEachNode(
        event.parent,
        instance -> {
          Reference reference = instance.getClassifier().getReferenceByMetaPointer(event.reference);
          if (reference == null) {
            throw new IllegalStateException(
                "Reference not found for " + instance + " using metapointer " + event.reference);
          }
          instance.removeReferenceValue(reference, event.index);
          instance.addReferenceValue(
              reference,
              event.index,
              new ReferenceValue(new ProxyNode(event.newReference), event.newResolveInfo));
        });
  }

  private void onReferenceDeleted(@NotNull ReferenceDeleted event) {
    forEachNode(
        event.parent,
        instance -> {
          Reference reference = instance.getClassifier().getReferenceByMetaPointer(event.reference);
          if (reference == null) {
            throw new IllegalStateException(
                "Reference not found for " + instance + " using metapointer " + event.reference);
          }
          instance.removeReferenceValue(reference, event.index);
        });
  }

  private void onChildMovedInSameContainment(@NotNull ChildMovedInSameContainment event) {
    Objects.requireNonNull(event, "event must not be null");
    forEachNode(
        event.parent,
        instance -> {
          Containment containment =
              instance.getClassifier().getContainmentByMetaPointer(event.containment);
          if (containment == null)
            throw new IllegalStateException(
                "Containment not found for "
                    + instance
                    + " using metapointer "
                    + event.containment);
          Node child = instance.getChildren(containment).get(event.oldIndex);
          instance.removeChild(containment, event.oldIndex);
          instance.addChild(containment, child, event.oldIndex + event.indexOffset);
        });
  }

  private void onChildMovedFromOtherContainmentInSameParent(
      @NotNull ChildMovedFromOtherContainmentInSameParent event) {
    Objects.requireNonNull(event, "event must not be null");
    forEachNode(
        event.parent,
        instance -> {
          Containment oldContainment =
              instance.getClassifier().getContainmentByMetaPointer(event.oldContainment);
          Containment newContainment =
              instance.getClassifier().getContainmentByMetaPointer(event.newContainment);
          if (oldContainment == null)
            throw new IllegalStateException(
                "Old containment not found for "
                    + instance
                    + " using metapointer "
                    + event.oldContainment);
          if (newContainment == null)
            throw new IllegalStateException(
                "New containment not found for "
                    + instance
                    + " using metapointer "
                    + event.newContainment);
          Node child = instance.getChildren(oldContainment).get(event.oldIndex);
          instance.removeChild(oldContainment, event.oldIndex);
          instance.addChild(newContainment, child, event.newIndex);
        });
  }

  private void onChildMovedFromOtherContainment(@NotNull ChildMovedFromOtherContainment event) {
    Objects.requireNonNull(event, "event must not be null");
    forEachNode(
        event.oldParent,
        oldInstance -> {
          Containment oldContainment =
              oldInstance.getClassifier().getContainmentByMetaPointer(event.oldContainment);
          if (oldContainment == null)
            throw new IllegalStateException(
                "Old containment not found for "
                    + oldInstance
                    + " using metapointer "
                    + event.oldContainment);
          Node child = oldInstance.getChildren(oldContainment).get(event.oldIndex);
          oldInstance.removeChild(oldContainment, event.oldIndex);
          forEachNode(
              event.newParent,
              newInstance -> {
                Containment newContainment =
                    newInstance.getClassifier().getContainmentByMetaPointer(event.newContainment);
                if (newContainment == null)
                  throw new IllegalStateException(
                      "New containment not found for "
                          + newInstance
                          + " using metapointer "
                          + event.newContainment);
                newInstance.addChild(newContainment, child, event.newIndex);
                monitorNode(child);
              });
        });
  }

  private void onChildReplaced(@NotNull ChildReplaced event) {
    Objects.requireNonNull(event, "event must not be null");
    forEachNode(
        event.parent,
        instance -> {
          Containment containment =
              instance.getClassifier().getContainmentByMetaPointer(event.containment);
          if (containment == null)
            throw new IllegalStateException(
                "Containment not found for "
                    + instance
                    + " using metapointer "
                    + event.containment);
          instance.removeChild(containment, event.index);
          Node newChild =
              (Node)
                  serialization
                      .deserializeSerializationChunk(withSerializationFormatVersion(event.newChild))
                      .get(0);
          monitorNode(newChild);
          instance.addChild(containment, newChild, event.index);
        });
  }

  private void onAnnotationAdded(@NotNull AnnotationAdded event) {
    forEachNode(
        event.parent,
        instance -> {
          AnnotationInstance annotation =
              (AnnotationInstance)
                  serialization
                      .deserializeSerializationChunk(
                          withSerializationFormatVersion(event.newAnnotation))
                      .get(0);
          instance.addAnnotation(annotation);
        });
  }

  private void onAnnotationDeleted(@NotNull AnnotationDeleted event) {
    forEachNode(
        event.parent,
        instance -> {
          instance.getAnnotations().stream()
              .filter(a -> event.deletedAnnotation.equals(a.getID()))
              .findFirst()
              .ifPresent(instance::removeAnnotation);
        });
  }

  private void onClassifierChanged(@NotNull ClassifierChanged event) {
    // Changing the runtime type of a local Java object is not possible.
    // We perhaps could make it work for Dynamic Nodes
    throw new UnsupportedOperationException(
        "Classifier changes are not yet supported by LionWeb JVM");
  }

  private void onErrorEvent(@NotNull ErrorEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    throw new ErrorEventReceivedException(event.errorCode, event.message);
  }

  /** Applies {@code action} to every live local instance tracked under {@code nodeId}. */
  private void forEachNode(
      @NotNull String nodeId, @NotNull java.util.function.Consumer<ClassifierInstance<?>> action) {
    Set<WeakReference<ClassifierInstance<?>>> refs = nodes.get(nodeId);
    if (refs == null) return;
    for (WeakReference<ClassifierInstance<?>> ref : refs) {
      ClassifierInstance<?> instance = ref.get();
      if (instance != null) action.accept(instance);
    }
  }

  /**
   * Represents the participation state of a user or entity in a system.
   *
   * <p>This enum defines the various states that indicate whether a user or entity is connected,
   * disconnected, or signed off from a system or session.
   */
  public enum ParticipationState {
    NOT_CONNECTED,
    CONNECTED,
    SIGNED_OFF,
  }

  private class MonitoringObserver implements PartitionObserver {

    public boolean paused = false;

    @Override
    public void propertyChanged(
        @NotNull ClassifierInstance<?> classifierInstance,
        @NotNull Property property,
        @Nullable Object oldValue,
        @Nullable Object newValue) {
      Objects.requireNonNull(classifierInstance, "classifierInstance must not be null");
      Objects.requireNonNull(property, "property must not be null");
      if (paused) return;
      String typeId = property.getType() != null ? property.getType().getID() : null;
      String serializedNew =
          newValue != null ? dataTypesValuesSerialization.serialize(typeId, newValue) : null;
      MetaPointer mp = MetaPointer.from(property);
      String nodeId = classifierInstance.getID();
      if (oldValue == null) {
        channel.sendCommand(
            ensureParticipation(),
            commandId -> new AddProperty(commandId, nodeId, mp, serializedNew));
      } else if (newValue == null) {
        channel.sendCommand(
            ensureParticipation(), commandId -> new DeleteProperty(commandId, nodeId, mp));
      } else {
        channel.sendCommand(
            ensureParticipation(),
            commandId -> new ChangeProperty(commandId, nodeId, mp, serializedNew));
      }
    }

    @Override
    public void childAdded(
        @NotNull ClassifierInstance<?> classifierInstance,
        @NotNull Containment containment,
        int index,
        @NotNull Node newChild) {
      Objects.requireNonNull(classifierInstance, "classifierInstance must not be null");
      Objects.requireNonNull(containment, "containment must not be null");
      Objects.requireNonNull(newChild, "newChild must not be null");
      if (paused) return;
      SerializationChunk chunk = serialization.serializeNodesToSerializationChunk(newChild);
      if (newChild.getID() == null) {
        throw new IllegalStateException("Child id must not be null");
      }
      channel.sendCommand(
          ensureParticipation(),
          commandId ->
              new AddChild(
                  commandId,
                  classifierInstance.getID(),
                  chunk,
                  MetaPointer.from(containment),
                  index));
    }

    @Override
    public void childRemoved(
        @NotNull ClassifierInstance<?> classifierInstance,
        @NotNull Containment containment,
        int index,
        @NotNull Node removedChild) {
      if (paused) return;
      Objects.requireNonNull(removedChild, "removedChild must not be null");
      String removedChildId = removedChild.getID();
      Objects.requireNonNull(removedChildId, "removedChildId must not be null");
      channel.sendCommand(
          ensureParticipation(),
          commandId ->
              new DeleteChild(
                  commandId,
                  classifierInstance.getID(),
                  MetaPointer.from(containment),
                  index,
                  removedChildId));
    }

    @Override
    public void annotationAdded(
        @NotNull ClassifierInstance<?> node, int index, @NotNull AnnotationInstance newAnnotation) {
      if (paused) return;
      SerializationChunk chunk = serialization.serializeTreeToSerializationChunk(newAnnotation);
      channel.sendCommand(
          ensureParticipation(),
          commandId -> new AddAnnotation(commandId, node.getID(), chunk, index));
    }

    @Override
    public void annotationRemoved(
        @NotNull ClassifierInstance<?> node,
        int index,
        @NotNull AnnotationInstance removedAnnotation) {
      if (paused) return;
      channel.sendCommand(
          ensureParticipation(),
          commandId ->
              new DeleteAnnotation(commandId, node.getID(), index, removedAnnotation.getID()));
    }

    @Override
    public void referenceValueAdded(
        @NotNull ClassifierInstance<?> classifierInstance,
        @NotNull Reference reference,
        int index,
        @NotNull ReferenceValue referenceValue) {
      Objects.requireNonNull(classifierInstance, "classifierInstance must not be null");
      Objects.requireNonNull(reference, "reference must not be null");
      Objects.requireNonNull(referenceValue, "referenceValue must not be null");
      if (paused) return;
      channel.sendCommand(
          ensureParticipation(),
          commandId ->
              new AddReference(
                  commandId,
                  classifierInstance.getID(),
                  MetaPointer.from(reference),
                  index,
                  referenceValue.getReferredID(),
                  referenceValue.getResolveInfo()));
    }

    @Override
    public void referenceValueChanged(
        @NotNull ClassifierInstance<?> classifierInstance,
        @NotNull Reference reference,
        int index,
        @Nullable String oldReferred,
        @Nullable String oldResolveInfo,
        @Nullable String newReferred,
        @Nullable String newResolveInfo) {
      Objects.requireNonNull(classifierInstance, "classifierInstance must not be null");
      Objects.requireNonNull(reference, "reference must not be null");
      if (paused) return;
      channel.sendCommand(
          ensureParticipation(),
          commandId ->
              new ChangeReference(
                  commandId,
                  classifierInstance.getID(),
                  MetaPointer.from(reference),
                  index,
                  oldReferred,
                  oldResolveInfo,
                  newReferred,
                  newResolveInfo));
    }

    @Override
    public void referenceValueRemoved(
        @NotNull ClassifierInstance<?> classifierInstance,
        @NotNull Reference reference,
        int index,
        @NotNull ReferenceValue referenceValue) {
      Objects.requireNonNull(classifierInstance, "classifierInstance must not be null");
      Objects.requireNonNull(reference, "reference must not be null");
      Objects.requireNonNull(referenceValue, "referenceValue must not be null");
      if (paused) return;
      channel.sendCommand(
          ensureParticipation(),
          commandId ->
              new DeleteReference(
                  commandId,
                  classifierInstance.getID(),
                  MetaPointer.from(reference),
                  index,
                  referenceValue.getReferredID(),
                  referenceValue.getResolveInfo()));
    }
  }
}
