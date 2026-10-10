package io.lionweb.client.inmemory;

import io.lionweb.client.delta.CommandSource;
import io.lionweb.client.delta.DeltaChannel;
import io.lionweb.client.delta.DeltaCommandReceiver;
import io.lionweb.client.delta.messages.DeltaCommand;
import io.lionweb.client.delta.messages.DeltaEvent;
import io.lionweb.client.delta.messages.commands.ChangeClassifier;
import io.lionweb.client.delta.messages.commands.annotations.*;
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
import io.lionweb.client.delta.messages.events.StandardErrorCode;
import io.lionweb.client.delta.messages.events.annotations.*;
import io.lionweb.client.delta.messages.events.children.*;
import io.lionweb.client.delta.messages.events.partitions.PartitionAdded;
import io.lionweb.client.delta.messages.events.partitions.PartitionDeleted;
import io.lionweb.client.delta.messages.events.properties.PropertyAdded;
import io.lionweb.client.delta.messages.events.properties.PropertyChanged;
import io.lionweb.client.delta.messages.events.properties.PropertyDeleted;
import io.lionweb.client.delta.messages.events.references.ReferenceAdded;
import io.lionweb.client.delta.messages.events.references.ReferenceChanged;
import io.lionweb.client.delta.messages.events.references.ReferenceDeleted;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import io.lionweb.serialization.data.SerializedReferenceValue;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * Implementation of {@link DeltaCommandReceiver} that receives commands from the server and applies
 * them to the in-memory server.
 */
class DeltaCommandReceiverImpl implements DeltaCommandReceiver {
  private final @NotNull String repositoryName;
  private final @NotNull DeltaChannel channel;
  private final @NotNull ParticipationManager participationManager;
  private final @NotNull InMemoryServer inMemoryServer;

  DeltaCommandReceiverImpl(
      @NotNull String repositoryName,
      @NotNull DeltaChannel channel,
      @NotNull ParticipationManager participationManager,
      @NotNull InMemoryServer inMemoryServer) {
    this.repositoryName = repositoryName;
    this.channel = channel;
    this.participationManager = participationManager;
    this.inMemoryServer = inMemoryServer;
  }

  @Override
  public void receiveCommand(@NotNull String participationId, @NotNull DeltaCommand command) {
    if (!participationManager.isActiveParticipation(participationId)) {
      // Error events are sent only to the participation which issued the command
      inMemoryServer.sendEvent(
          channel,
          List.of(participationId),
          sequenceNumber ->
              new ErrorEvent(
                  sequenceNumber,
                  StandardErrorCode.INVALID_PARTICIPATION,
                  "Invalid participation: " + participationId));
      return;
    }
    CommandSource source = new CommandSource(participationId, command.commandId);
    RepositoryData data = inMemoryServer.getRepository(repositoryName);
    try {
      if (command instanceof ChangeProperty)
        handleChangeProperty((ChangeProperty) command, data, source);
      else if (command instanceof AddChild) handleAddChild((AddChild) command, data, source);
      else if (command instanceof DeleteChild)
        handleDeleteChild((DeleteChild) command, data, source);
      else if (command instanceof AddReference)
        handleAddReference((AddReference) command, data, source);
      else if (command instanceof AddAnnotation)
        handleAddAnnotation((AddAnnotation) command, data, source);
      else if (command instanceof DeleteAnnotation)
        handleDeleteAnnotation((DeleteAnnotation) command, data, source);
      else if (command instanceof MoveAnnotationInSameParent)
        handleMoveAnnotationInSameParent((MoveAnnotationInSameParent) command, data, source);
      else if (command instanceof MoveAnnotationFromOtherParent)
        handleMoveAnnotationFromOtherParent((MoveAnnotationFromOtherParent) command, data, source);
      else if (command instanceof ReplaceAnnotation)
        handleReplaceAnnotation((ReplaceAnnotation) command, data, source);
      else if (command instanceof MoveChildInSameContainment)
        handleMoveChildInSameContainment((MoveChildInSameContainment) command, data, source);
      else if (command instanceof MoveChildFromOtherContainmentInSameParent)
        handleMoveChildFromOtherContainmentInSameParent(
            (MoveChildFromOtherContainmentInSameParent) command, data, source);
      else if (command instanceof MoveChildFromOtherContainment)
        handleMoveChildFromOtherContainment((MoveChildFromOtherContainment) command, data, source);
      else if (command instanceof ReplaceChild)
        handleReplaceChild((ReplaceChild) command, data, source);
      else if (command instanceof AddProperty)
        handleAddProperty((AddProperty) command, data, source);
      else if (command instanceof DeleteProperty)
        handleDeleteProperty((DeleteProperty) command, data, source);
      else if (command instanceof ChangeReference)
        handleChangeReference((ChangeReference) command, data, source);
      else if (command instanceof DeleteReference)
        handleDeleteReference((DeleteReference) command, data, source);
      else if (command instanceof ChangeClassifier)
        handleChangeClassifier((ChangeClassifier) command, data, source);
      else if (command instanceof AddPartition) handleAddPartition((AddPartition) command, source);
      else if (command instanceof DeletePartition)
        handleDeletePartition((DeletePartition) command, data, source);
      else
        throw new UnsupportedOperationException(
            "Unsupported command type: " + command.getClass().getName());
    } catch (NodeNotFoundException e) {
      String msg = e.getMessage();
      inMemoryServer.sendEvent(
          channel,
          List.of(participationId),
          seqNum -> new ErrorEvent(seqNum, StandardErrorCode.UNKNOWN_NODE, msg));
    }
  }

  private void handleChangeProperty(ChangeProperty cmd, RepositoryData data, CommandSource source) {
    SerializedClassifierInstance node = requireNode(data, cmd.node);
    String oldValue = node.getPropertyValue(cmd.property);
    node.setPropertyValue(cmd.property, cmd.newValue);
    String newValue = node.getPropertyValue(cmd.property);
    sendEventToSubscribers(
        partitionsOf(data, cmd.node),
        seqNum ->
            new PropertyChanged(seqNum, node.getID(), cmd.property, newValue, oldValue)
                .addSource(source));
  }

  private void handleAddChild(AddChild cmd, RepositoryData data, CommandSource source) {
    SerializedClassifierInstance parent = requireNode(data, cmd.parent);
    data.store(cmd.newChild.getClassifierInstances());
    String childId =
        cmd.newChild.getClassifierInstances().stream()
            .filter(n -> cmd.parent.equals(n.getParentNodeID()))
            .findFirst()
            .orElseThrow()
            .getID();
    parent.addChild(cmd.containment, childId, cmd.index);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new ChildAdded(seqNum, cmd.parent, cmd.newChild, cmd.containment, cmd.index)
                .addSource(source));
  }

  private void handleDeleteChild(DeleteChild cmd, RepositoryData data, CommandSource source) {
    requireNode(data, cmd.parent);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new ChildDeleted(
                    seqNum, cmd.parent, cmd.deletedChild, List.of(), cmd.index, cmd.containment)
                .addSource(source));
  }

  private void handleAddReference(AddReference cmd, RepositoryData data, CommandSource source) {
    SerializedClassifierInstance node = requireNode(data, cmd.parent);
    node.addReferenceValue(
        cmd.reference,
        cmd.index,
        new SerializedReferenceValue.Entry(cmd.newReference, cmd.newResolveInfo));
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new ReferenceAdded(
                    seqNum,
                    cmd.parent,
                    cmd.reference,
                    cmd.index,
                    cmd.newReference,
                    cmd.newResolveInfo)
                .addSource(source));
  }

  private void handleAddAnnotation(
      @NotNull AddAnnotation cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    SerializedClassifierInstance parent = requireNode(data, cmd.parent);
    Set<String> chunkIds =
        cmd.newAnnotation.getClassifierInstances().stream()
            .map(SerializedClassifierInstance::getID)
            .collect(Collectors.toSet());
    SerializedClassifierInstance annotationRoot =
        cmd.newAnnotation.getClassifierInstances().stream()
            .filter(n -> n.getParentNodeID() == null || !chunkIds.contains(n.getParentNodeID()))
            .findFirst()
            .orElseThrow(() -> new NodeNotFoundException("annotation root not found in chunk"));
    annotationRoot.setParentNodeID(cmd.parent);
    data.store(cmd.newAnnotation.getClassifierInstances());
    List<String> annotations = new ArrayList<>(parent.getAnnotations());
    annotations.add(cmd.index, annotationRoot.getID());
    parent.setAnnotations(annotations);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new AnnotationAdded(seqNum, cmd.parent, cmd.newAnnotation, cmd.index)
                .addSource(source));
  }

  private void handleDeleteAnnotation(
      @NotNull DeleteAnnotation cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    SerializedClassifierInstance parent = requireNode(data, cmd.parent);
    List<String> descendants = new ArrayList<>();
    collectDescendants(data, cmd.deletedAnnotation, descendants);
    List<String> annotations = new ArrayList<>(parent.getAnnotations());
    annotations.remove(cmd.index);
    parent.setAnnotations(annotations);
    data.deleteNodeAndDescendant(cmd.deletedAnnotation);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new AnnotationDeleted(
                    seqNum,
                    cmd.deletedAnnotation,
                    descendants.toArray(new String[0]),
                    cmd.parent,
                    cmd.index)
                .addSource(source));
  }

  private void handleMoveAnnotationInSameParent(
      @NotNull MoveAnnotationInSameParent cmd,
      @NotNull RepositoryData data,
      @NotNull CommandSource source) {
    SerializedClassifierInstance parent = requireNode(data, cmd.parent);
    List<String> annotations = new ArrayList<>(parent.getAnnotations());
    String id = annotations.remove(cmd.oldIndex);
    int newIndex = cmd.oldIndex + cmd.indexOffset;
    annotations.add(newIndex, id);
    parent.setAnnotations(annotations);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new AnnotationMovedInSameParent(
                    seqNum, cmd.indexOffset, cmd.movedAnnotation, cmd.parent, cmd.oldIndex)
                .addSource(source));
  }

  private void handleMoveAnnotationFromOtherParent(
      @NotNull MoveAnnotationFromOtherParent cmd,
      @NotNull RepositoryData data,
      @NotNull CommandSource source) {
    SerializedClassifierInstance oldParent = requireNode(data, cmd.oldParent);
    SerializedClassifierInstance newParent = requireNode(data, cmd.newParent);
    SerializedClassifierInstance annotation = requireNode(data, cmd.movedAnnotation);
    List<String> oldAnnotations = new ArrayList<>(oldParent.getAnnotations());
    oldAnnotations.remove(cmd.oldIndex);
    oldParent.setAnnotations(oldAnnotations);
    annotation.setParentNodeID(cmd.newParent);
    List<String> newAnnotations = new ArrayList<>(newParent.getAnnotations());
    newAnnotations.add(cmd.newIndex, cmd.movedAnnotation);
    newParent.setAnnotations(newAnnotations);
    sendEventToSubscribers(
        partitionsOf(data, cmd.oldParent, cmd.newParent),
        seqNum ->
            new AnnotationMovedFromOtherParent(
                    seqNum,
                    cmd.newParent,
                    cmd.newIndex,
                    cmd.movedAnnotation,
                    cmd.oldParent,
                    cmd.oldIndex)
                .addSource(source));
  }

  private void handleReplaceAnnotation(
      @NotNull ReplaceAnnotation cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    SerializedClassifierInstance parent = requireNode(data, cmd.parent);
    List<String> descendants = new ArrayList<>();
    collectDescendants(data, cmd.replacedAnnotation, descendants);
    Set<String> chunkIds =
        cmd.newAnnotation.getClassifierInstances().stream()
            .map(SerializedClassifierInstance::getID)
            .collect(Collectors.toSet());
    SerializedClassifierInstance newRoot =
        cmd.newAnnotation.getClassifierInstances().stream()
            .filter(n -> n.getParentNodeID() == null || !chunkIds.contains(n.getParentNodeID()))
            .findFirst()
            .orElseThrow(() -> new NodeNotFoundException("new annotation root not found"));
    newRoot.setParentNodeID(cmd.parent);
    List<String> annotations = new ArrayList<>(parent.getAnnotations());
    annotations.remove(cmd.index);
    parent.setAnnotations(annotations);
    data.deleteNodeAndDescendant(cmd.replacedAnnotation);
    data.store(cmd.newAnnotation.getClassifierInstances());
    annotations.add(cmd.index, newRoot.getID());
    parent.setAnnotations(annotations);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new AnnotationReplaced(
                    seqNum,
                    cmd.newAnnotation,
                    cmd.replacedAnnotation,
                    descendants.toArray(new String[0]),
                    cmd.parent,
                    cmd.index)
                .addSource(source));
  }

  private void handleMoveChildInSameContainment(
      @NotNull MoveChildInSameContainment cmd,
      @NotNull RepositoryData data,
      @NotNull CommandSource source) {
    SerializedClassifierInstance parent = requireNode(data, cmd.parent);
    parent.removeChild(cmd.movedChild);
    int newIndex = cmd.oldIndex + cmd.indexOffset;
    parent.addChild(cmd.containment, cmd.movedChild, newIndex);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new ChildMovedInSameContainment(
                    seqNum,
                    cmd.indexOffset,
                    cmd.movedChild,
                    cmd.parent,
                    cmd.containment,
                    cmd.oldIndex)
                .addSource(source));
  }

  private void handleMoveChildFromOtherContainmentInSameParent(
      @NotNull MoveChildFromOtherContainmentInSameParent cmd,
      @NotNull RepositoryData data,
      @NotNull CommandSource source) {
    SerializedClassifierInstance parent = requireNode(data, cmd.parent);
    parent.removeChild(cmd.movedChild);
    parent.addChild(cmd.newContainment, cmd.movedChild, cmd.newIndex);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum -> {
          ChildMovedFromOtherContainmentInSameParent event =
              new ChildMovedFromOtherContainmentInSameParent(
                  seqNum, cmd.newContainment, cmd.newIndex, cmd.movedChild);
          event.parent = cmd.parent;
          event.oldContainment = cmd.oldContainment;
          event.oldIndex = cmd.oldIndex;
          return event.addSource(source);
        });
  }

  private void handleMoveChildFromOtherContainment(
      @NotNull MoveChildFromOtherContainment cmd,
      @NotNull RepositoryData data,
      @NotNull CommandSource source) {
    SerializedClassifierInstance oldParent = requireNode(data, cmd.oldParent);
    SerializedClassifierInstance newParent = requireNode(data, cmd.newParent);
    SerializedClassifierInstance child = requireNode(data, cmd.movedChild);
    oldParent.removeChild(cmd.movedChild);
    child.setParentNodeID(cmd.newParent);
    newParent.addChild(cmd.newContainment, cmd.movedChild, cmd.newIndex);
    sendEventToSubscribers(
        partitionsOf(data, cmd.oldParent, cmd.newParent),
        seqNum -> {
          ChildMovedFromOtherContainment event =
              new ChildMovedFromOtherContainment(
                  seqNum, cmd.newParent, cmd.newContainment, cmd.newIndex, cmd.movedChild);
          event.oldParent = cmd.oldParent;
          event.oldContainment = cmd.oldContainment;
          event.oldIndex = cmd.oldIndex;
          return event.addSource(source);
        });
  }

  private void handleReplaceChild(
      @NotNull ReplaceChild cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    SerializedClassifierInstance parent = requireNode(data, cmd.parent);
    List<String> replacedDescendants = new ArrayList<>();
    collectDescendants(data, cmd.replacedChild, replacedDescendants);
    parent.removeChild(cmd.replacedChild);
    data.deleteNodeAndDescendant(cmd.replacedChild);
    // Find the root of the new child subtree: the node whose parent is NOT inside this chunk.
    Set<String> chunkIds =
        cmd.newChild.getClassifierInstances().stream()
            .map(SerializedClassifierInstance::getID)
            .collect(Collectors.toSet());
    SerializedClassifierInstance newChildRoot =
        cmd.newChild.getClassifierInstances().stream()
            .filter(n -> n.getParentNodeID() == null || !chunkIds.contains(n.getParentNodeID()))
            .findFirst()
            .orElseThrow(() -> new NodeNotFoundException("root of replacement chunk not found"));
    newChildRoot.setParentNodeID(cmd.parent);
    data.store(cmd.newChild.getClassifierInstances());
    parent.addChild(cmd.containment, newChildRoot.getID(), cmd.index);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new ChildReplaced(
                    seqNum,
                    cmd.parent,
                    cmd.newChild,
                    cmd.replacedChild,
                    replacedDescendants,
                    cmd.containment,
                    cmd.index)
                .addSource(source));
  }

  private void handleAddProperty(
      @NotNull AddProperty cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    SerializedClassifierInstance node = requireNode(data, cmd.node);
    node.setPropertyValue(cmd.property, cmd.newValue);
    sendEventToSubscribers(
        partitionsOf(data, cmd.node),
        seqNum ->
            new PropertyAdded(seqNum, cmd.node, cmd.property, cmd.newValue).addSource(source));
  }

  private void handleDeleteProperty(
      @NotNull DeleteProperty cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    SerializedClassifierInstance node = requireNode(data, cmd.node);
    String oldValue = node.getPropertyValue(cmd.property);
    node.setPropertyValue(cmd.property, null);
    sendEventToSubscribers(
        partitionsOf(data, cmd.node),
        seqNum -> new PropertyDeleted(seqNum, cmd.node, cmd.property, oldValue).addSource(source));
  }

  private void handleChangeReference(
      @NotNull ChangeReference cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    SerializedClassifierInstance node = requireNode(data, cmd.parent);
    List<SerializedReferenceValue.Entry> entries =
        new ArrayList<>(node.getReferenceValues(cmd.reference));
    entries.set(
        cmd.index, new SerializedReferenceValue.Entry(cmd.newReference, cmd.newResolveInfo));
    node.setReferenceValue(cmd.reference, entries);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new ReferenceChanged(
                    seqNum,
                    cmd.parent,
                    cmd.reference,
                    cmd.index,
                    cmd.newReference,
                    cmd.newResolveInfo,
                    cmd.oldReference,
                    cmd.oldResolveInfo)
                .addSource(source));
  }

  private void handleDeleteReference(
      @NotNull DeleteReference cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    SerializedClassifierInstance node = requireNode(data, cmd.parent);
    List<SerializedReferenceValue.Entry> entries =
        new ArrayList<>(node.getReferenceValues(cmd.reference));
    entries.remove(cmd.index);
    node.setReferenceValue(cmd.reference, entries);
    sendEventToSubscribers(
        partitionsOf(data, cmd.parent),
        seqNum ->
            new ReferenceDeleted(
                    seqNum,
                    cmd.parent,
                    cmd.reference,
                    cmd.index,
                    cmd.deletedReference,
                    cmd.deletedResolveInfo)
                .addSource(source));
  }

  private void handleChangeClassifier(
      @NotNull ChangeClassifier cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    SerializedClassifierInstance node = requireNode(data, cmd.node);
    io.lionweb.serialization.data.MetaPointer oldClassifier = node.getClassifier();
    node.setClassifier(cmd.newClassifier);
    sendEventToSubscribers(
        partitionsOf(data, cmd.node),
        seqNum ->
            new ClassifierChanged(seqNum, cmd.node, cmd.newClassifier, oldClassifier)
                .addSource(source));
  }

  private void handleAddPartition(@NotNull AddPartition cmd, @NotNull CommandSource source) {
    inMemoryServer.createPartitionFromChunk(
        repositoryName, cmd.newPartition.getClassifierInstances());
    // The participation creating a partition is automatically subscribed to it
    List<String> newPartitionIds =
        cmd.newPartition.getClassifierInstances().stream()
            .filter(n -> n.getParentNodeID() == null)
            .map(SerializedClassifierInstance::getID)
            .collect(Collectors.toList());
    newPartitionIds.forEach(
        id -> participationManager.subscribeToPartition(source.participationId, id));
    sendPartitionEvent(
        newPartitionIds, seqNum -> new PartitionAdded(seqNum, cmd.newPartition).addSource(source));
  }

  private void handleDeletePartition(
      @NotNull DeletePartition cmd, @NotNull RepositoryData data, @NotNull CommandSource source) {
    List<String> descendants = new ArrayList<>();
    collectDescendants(data, cmd.deletedPartition, descendants);
    inMemoryServer.deletePartitions(repositoryName, List.of(cmd.deletedPartition));
    sendPartitionEvent(
        List.of(cmd.deletedPartition),
        seqNum ->
            new PartitionDeleted(seqNum, cmd.deletedPartition, descendants).addSource(source));
    participationManager.removeAllSubscriptionsTo(cmd.deletedPartition);
  }

  /**
   * Sends the event to the participations subscribed to the contents of any of the given
   * partitions.
   */
  private void sendEventToSubscribers(
      @NotNull Collection<String> partitionIds,
      @NotNull Function<Integer, DeltaEvent> eventProducer) {
    inMemoryServer.sendEvent(
        repositoryName,
        participationManager.participationsSubscribedToAnyOf(partitionIds),
        eventProducer);
  }

  /**
   * Sends an event concerning the list of partitions: it is received by the participations
   * subscribed to the list of partitions and by the participations subscribed to the contents of
   * the given partitions.
   */
  private void sendPartitionEvent(
      @NotNull Collection<String> partitionIds,
      @NotNull Function<Integer, DeltaEvent> eventProducer) {
    Set<String> recipients =
        new LinkedHashSet<>(participationManager.participationsSubscribedToPartitionList());
    recipients.addAll(participationManager.participationsSubscribedToAnyOf(partitionIds));
    inMemoryServer.sendEvent(repositoryName, recipients, eventProducer);
  }

  /** Returns the IDs of the partitions containing the given nodes. */
  private @NotNull Set<String> partitionsOf(
      @NotNull RepositoryData data, @NotNull String... nodeIds) {
    Set<String> partitionIds = new LinkedHashSet<>();
    for (String nodeId : nodeIds) {
      SerializedClassifierInstance node = data.nodesByID.get(nodeId);
      while (node != null && node.getParentNodeID() != null) {
        node = data.nodesByID.get(node.getParentNodeID());
      }
      if (node != null) {
        partitionIds.add(node.getID());
      }
    }
    return partitionIds;
  }

  /** Returns the node or throws {@link NodeNotFoundException} if it does not exist. */
  private SerializedClassifierInstance requireNode(
      @NotNull RepositoryData data, @NotNull String nodeId) {
    List<SerializedClassifierInstance> retrieved = new ArrayList<>();
    try {
      data.retrieve(nodeId, 0, retrieved);
    } catch (IllegalArgumentException e) {
      throw new NodeNotFoundException(nodeId);
    }
    return retrieved.get(0);
  }

  private void collectDescendants(
      @NotNull RepositoryData data, @NotNull String nodeId, @NotNull List<String> descendants) {
    SerializedClassifierInstance node = data.nodesByID.get(nodeId);
    if (node == null) return;
    node.getChildren()
        .forEach(
            childId -> {
              descendants.add(childId);
              collectDescendants(data, childId, descendants);
            });
  }

  private class NodeNotFoundException extends RuntimeException {
    NodeNotFoundException(String nodeId) {
      super("Node with id " + nodeId + " not found");
    }
  }
}
