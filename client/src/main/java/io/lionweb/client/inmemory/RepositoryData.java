package io.lionweb.client.inmemory;

import io.lionweb.client.api.ClassifierKey;
import io.lionweb.client.api.ClassifierResult;
import io.lionweb.client.api.RepositoryConfiguration;
import io.lionweb.client.api.RepositoryVersionToken;
import io.lionweb.serialization.data.MetaPointer;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import io.lionweb.serialization.data.SerializedContainmentValue;
import io.lionweb.utils.IdUtils;
import io.lionweb.utils.ValidationResult;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds all node data for a single repository.
 *
 * <p><b>Thread-safety:</b> concurrent reads of {@link #nodesByID} are safe (it is a {@link
 * java.util.concurrent.ConcurrentHashMap}), but <em>mutations are not thread-safe</em>. Methods
 * such as {@link #store}, {@link #deleteNodeAndDescendant}, and the internal classifier index
 * updates perform multi-step, non-atomic operations across several data structures. Callers must
 * externally serialize all mutations. The {@code ConcurrentHashMap} for {@code nodesByID} should
 * not be mistaken for a broader thread-safety guarantee.
 *
 * <p>The transactional bulk commands ({@link #storeTransactional}, {@link
 * #createPartitionsTransactional}, {@link #deletePartitionsTransactional}, {@link
 * #retrieveReportingUnknown}) are meant to be called while holding {@link #lock} (write lock for
 * the first three, read lock for the last), as {@link InMemoryServer} does.
 */
class RepositoryData {
  @NotNull RepositoryConfiguration configuration;
  final Set<String> partitionIDs = new LinkedHashSet<>();
  final Map<String, SerializedClassifierInstance> nodesByID = new ConcurrentHashMap<>();

  /** Pre-computed index: ClassifierKey → list of node IDs. Kept in sync with nodesByID. */
  private final @Nullable Map<ClassifierKey, List<String>> classifierIndex;

  private final boolean materializeClassifierIndex;

  private int currentVersion = 0;
  private int nextId = 1;

  void deleteNodeAndDescendant(String nodeId) {
    SerializedClassifierInstance curr = nodesByID.get(nodeId);
    if (curr == null) {
      throw new IllegalArgumentException("Node " + nodeId + " does not exist");
    }
    indexRemove(curr);
    nodesByID.remove(nodeId);
    curr.getChildren().forEach(this::deleteNodeAndDescendant);
    curr.getAnnotations().forEach(this::deleteNodeAndDescendant);
  }

  private ClassifierKey classifierKeyOf(SerializedClassifierInstance node) {
    MetaPointer mp = node.getClassifier();
    return new ClassifierKey(mp.getLanguage(), mp.getKey());
  }

  private void indexAdd(SerializedClassifierInstance node) {
    if (!materializeClassifierIndex) return;
    ClassifierKey key = classifierKeyOf(node);
    classifierIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(node.getID());
  }

  private void indexRemove(SerializedClassifierInstance node) {
    if (!materializeClassifierIndex) return;
    ClassifierKey key = classifierKeyOf(node);
    List<String> ids = classifierIndex.get(key);
    if (ids != null) {
      ids.remove(node.getID());
      if (ids.isEmpty()) {
        classifierIndex.remove(key);
      }
    }
  }

  /**
   * Returns pre-computed classifier → result map, applying an optional node-ID limit per
   * classifier.
   */
  Map<ClassifierKey, ClassifierResult> nodesByClassifier(Integer limit) {
    int actualLimit = (limit != null) ? limit : Integer.MAX_VALUE;
    Map<ClassifierKey, List<String>> index;
    if (materializeClassifierIndex) {
      index = classifierIndex;
    } else {
      index = new HashMap<>();
      for (SerializedClassifierInstance node : nodesByID.values()) {
        index.computeIfAbsent(classifierKeyOf(node), k -> new ArrayList<>()).add(node.getID());
      }
    }
    Map<ClassifierKey, ClassifierResult> result = new HashMap<>(index.size() * 2);
    for (Map.Entry<ClassifierKey, List<String>> entry : index.entrySet()) {
      List<String> allIds = entry.getValue();
      int total = allIds.size();
      Set<String> limitedIds =
          new HashSet<>(actualLimit >= total ? allIds : allIds.subList(0, actualLimit));
      result.put(entry.getKey(), new ClassifierResult(limitedIds, total));
    }
    return result;
  }

  ClassifierResult nodesByClassifier(Integer limit, ClassifierKey key) {
    int actualLimit = (limit != null) ? limit : Integer.MAX_VALUE;
    List<String> allIds;
    if (materializeClassifierIndex) {
      allIds = classifierIndex.getOrDefault(key, List.of());
    } else {
      allIds = new ArrayList<>();
      for (SerializedClassifierInstance node : nodesByID.values()) {
        if (key.equals(classifierKeyOf(node))) {
          allIds.add(node.getID());
        }
      }
    }
    int total = allIds.size();
    Set<String> limitedIds =
        new HashSet<>(actualLimit >= total ? allIds : allIds.subList(0, actualLimit));
    return new ClassifierResult(limitedIds, total);
  }

  private class ChangeCalculator {
    private final Map<String, SerializedClassifierInstance> addedNodes = new HashMap<>();
    private final Set<String> removedNodes = new HashSet<>();

    /**
     * Given the two lists of nodes, it track the changes in terms of added or removed nodes
     *
     * @param updatedNodesAsMap the map id -> Node
     * @param oldState the list of nodes before the change
     * @param newState the list of nodes after the change
     * @param role the role of the list, used for debugging purposes
     */
    private void calculateNodeListDifferences(
        Map<String, SerializedClassifierInstance> updatedNodesAsMap,
        List<String> oldState,
        List<String> newState,
        String role) {
      Set<String> oldStateSet = new HashSet<>(oldState);
      Set<String> newStateSet = new HashSet<>(newState);
      for (String n : newState) {
        if (!oldStateSet.contains(n)) {
          this.addedNodes.put(n, updatedNodesAsMap.get(n));
        }
      }
      List<String> unknownNodes = null;
      for (String c : newState) {
        if (!updatedNodesAsMap.containsKey(c) && !nodesByID.containsKey(c)) {
          if (unknownNodes == null) unknownNodes = new ArrayList<>();
          unknownNodes.add(c);
        }
      }
      if (unknownNodes != null) {
        throw new IllegalArgumentException("We got unknown nodes as " + role + ": " + unknownNodes);
      }
      for (String n : oldState) {
        if (!newStateSet.contains(n)) {
          this.removedNodes.add(n);
        }
      }
    }

    void store(List<SerializedClassifierInstance> updatedNodes) {
      Map<String, SerializedClassifierInstance> updatedNodesAsMap = new HashMap<>();
      updatedNodes.forEach(n -> updatedNodesAsMap.put(n.getID(), n));
      for (SerializedClassifierInstance updatedNode : updatedNodes) {
        if (nodesByID.containsKey(updatedNode.getID())) {
          SerializedClassifierInstance currentNode = nodesByID.get(updatedNode.getID());
          if (currentNode.getParentNodeID() != null
              && !updatedNodesAsMap.containsKey(currentNode.getParentNodeID())) {
            // If the node currently has a parent, which has not been modified it can only means two
            // things:
            // - The node has changed parent, being removed from the old parent
            // - The node stayed where it was: same parent, same position
            if (!currentNode.getParentNodeID().equals(updatedNode.getParentNodeID())) {
              removeContainedNode(currentNode.getParentNodeID(), updatedNode.getID());
            }
          }
          calculateNodeListDifferences(
              updatedNodesAsMap,
              nodesByID.get(updatedNode.getID()).getChildren(),
              updatedNode.getChildren(),
              "children");
          calculateNodeListDifferences(
              updatedNodesAsMap,
              nodesByID.get(updatedNode.getID()).getAnnotations(),
              updatedNode.getAnnotations(),
              "annotations");
        }
      }
      // They have been moved and not removed
      removedNodes.removeAll(addedNodes.keySet());
      // Update classifier index for new/updated nodes before replacing nodesByID entries
      for (SerializedClassifierInstance updatedNode : updatedNodes) {
        SerializedClassifierInstance existing = nodesByID.get(updatedNode.getID());
        if (existing != null) {
          // Handle potential classifier change (rare but correct)
          ClassifierKey oldKey = classifierKeyOf(existing);
          ClassifierKey newKey = classifierKeyOf(updatedNode);
          if (!oldKey.equals(newKey)) {
            indexRemove(existing);
            indexAdd(updatedNode);
          }
        } else {
          indexAdd(updatedNode);
        }
      }
      nodesByID.putAll(updatedNodesAsMap);
      removedNodes.forEach(this::removeNode);
    }

    private void removeNode(String removeNodeId) {
      SerializedClassifierInstance serializedClassifierInstance = nodesByID.get(removeNodeId);
      for (String childId : serializedClassifierInstance.getChildren()) {
        if (!addedNodes.containsKey(childId)) {
          removeNode(childId);
        }
      }
      for (String annotationId : serializedClassifierInstance.getAnnotations()) {
        if (!addedNodes.containsKey(annotationId)) {
          removeNode(annotationId);
        }
      }
      indexRemove(serializedClassifierInstance);
      nodesByID.remove(removeNodeId);
    }
  }

  /**
   * Removes a contained node (a child or an annotation) from the specified container in the
   * repository data.
   *
   * @param containerId the identifier of the container node from which the contained node should be
   *     removed
   * @param containedId the identifier of the contained node to be removed
   */
  private void removeContainedNode(String containerId, String containedId) {
    SerializedClassifierInstance container = nodesByID.get(containerId);
    container.getContainments().forEach(containment -> containment.removeChild(containedId));
    container.removeAnnotation(containedId);
  }

  RepositoryData(@NotNull RepositoryConfiguration configuration) {
    this(configuration, true);
  }

  RepositoryData(
      @NotNull RepositoryConfiguration configuration, boolean materializeClassifierIndex) {
    this.configuration = configuration;
    this.materializeClassifierIndex = materializeClassifierIndex;
    this.classifierIndex = materializeClassifierIndex ? new HashMap<>() : null;
  }

  RepositoryVersionToken bumpVersion() {
    return new RepositoryVersionToken("v-" + ++currentVersion);
  }

  List<String> ids(int count) {
    List<String> res = new ArrayList<>(count);
    while (res.size() < count) {
      String candidate = "id-" + (nextId++);
      if (!nodesByID.containsKey(candidate)) {
        res.add(candidate);
      }
    }
    return res;
  }

  void store(List<SerializedClassifierInstance> newNodes) {
    newNodes.stream()
        .filter(n -> n.getParentNodeID() == null)
        .forEach(
            n -> {
              if (!partitionIDs.contains(n.getID())) {
                throw new IllegalArgumentException(
                    "Node " + n + " should be registered as a partition");
              }
            });
    new ChangeCalculator().store(newNodes);
  }

  //
  // Transactional commands of the bulk API.
  //
  // Each command first validates the whole request against the current content, without touching
  // it, and builds a plan of what has to change. Only when the request is valid the plan is
  // applied, and applying it cannot fail. A rejected command thus leaves nodes, partitions and
  // indexes exactly as they were.
  //

  /** Read-write lock used by {@link InMemoryServer} to serialize the bulk commands. */
  final ReadWriteLock lock = new ReentrantReadWriteLock();

  private static final class StorePlan {
    final Map<String, SerializedClassifierInstance> requested = new LinkedHashMap<>();
    final Set<String> newPartitions = new LinkedHashSet<>();

    /** Nodes not in the request which are moved under a node in the request. */
    final Map<String, String> implicitlyMovedTo = new LinkedHashMap<>();

    /** Nodes not in the request which stop being contained by their old parent. */
    final List<String[]> implicitlyRemovedFrom = new ArrayList<>();

    /** Nodes to delete, including descendants. */
    final Set<String> removed = new LinkedHashSet<>();
  }

  /**
   * Stores the nodes, as the {@code store} command of the bulk API does. If the request would lead
   * to a malformed tree a {@link BulkCommandException} is thrown, and nothing is changed.
   */
  void storeTransactional(List<SerializedClassifierInstance> nodes) {
    apply(plan(nodes, false));
  }

  /**
   * Creates new partitions, as the {@code createPartitions} command of the bulk API does. Nodes
   * without parent are the new partitions. For backward compatibility the request may also contain
   * their (new) descendants. If the request is invalid a {@link BulkCommandException} is thrown,
   * and nothing is changed.
   */
  void createPartitionsTransactional(List<SerializedClassifierInstance> nodes) {
    apply(plan(nodes, true));
  }

  /**
   * Deletes partitions with all their contents, as the {@code deletePartitions} command of the bulk
   * API does. Unknown ids are ignored. If any of the ids is not the id of a partition a {@link
   * BulkCommandException} is thrown, and nothing is changed.
   */
  void deletePartitionsTransactional(List<String> ids) {
    Set<String> partitions = partitionIDs;
    Set<String> toDelete = new LinkedHashSet<>();
    Deque<String> pending = new ArrayDeque<>();
    for (String id : new LinkedHashSet<>(ids)) {
      SerializedClassifierInstance node = nodesByID.get(id);
      if (node == null) {
        continue;
      }
      if (!partitions.contains(id)) {
        throw BulkCommandException.of(
            BulkMessageKind.NODE_IS_NOT_PARTITION,
            "Node " + id + " is not a partition",
            "nodeId",
            id,
            "parentNodeId",
            String.valueOf(node.getParentNodeID()));
      }
      pending.add(id);
    }
    while (!pending.isEmpty()) {
      String id = pending.poll();
      SerializedClassifierInstance node = nodesByID.get(id);
      if (node == null || !toDelete.add(id)) {
        continue;
      }
      pending.addAll(node.getChildren());
      pending.addAll(node.getAnnotations());
    }
    partitionIDs.removeAll(toDelete);
    toDelete.forEach(this::removeFromIndexAndNodes);
  }

  private void removeFromIndexAndNodes(String id) {
    SerializedClassifierInstance node = nodesByID.remove(id);
    if (node != null) {
      indexRemove(node);
    }
  }

  private static BulkCommandException parentMissing(String nodeId, String message) {
    return BulkCommandException.of(BulkMessageKind.PARENT_MISSING, message, "nodeId", nodeId);
  }

  private StorePlan plan(List<SerializedClassifierInstance> nodes, boolean creatingPartitions) {
    if (nodes == null) {
      throw BulkCommandException.of(BulkMessageKind.NULL_CHUNK, "Chunk missing");
    }
    StorePlan plan = new StorePlan();
    for (SerializedClassifierInstance n : nodes) {
      if (plan.requested.put(n.getID(), n) != null) {
        throw BulkCommandException.of(
            BulkMessageKind.DUPLICATE_NODE_ID,
            "Node " + n.getID() + " is sent more than once",
            "nodeId",
            n.getID());
      }
    }
    Set<String> newPartitions = plan.newPartitions;
    java.util.function.Predicate<String> partitions =
        id -> partitionIDs.contains(id) || newPartitions.contains(id);
    if (creatingPartitions) {
      for (SerializedClassifierInstance n : nodes) {
        String id = n.getID();
        if (n.getParentNodeID() == null) {
          if (nodesByID.containsKey(id) || partitionIDs.contains(id)) {
            throw BulkCommandException.of(
                BulkMessageKind.PARTITION_ALREADY_EXISTS,
                "Partition " + id + " already exists",
                "nodeId",
                id);
          }
          plan.newPartitions.add(id);
        } else {
          if (!plan.requested.containsKey(n.getParentNodeID())) {
            throw BulkCommandException.of(
                BulkMessageKind.PARTITION_HAS_PARENT,
                "Partition " + id + " has parent " + n.getParentNodeID(),
                "nodeId",
                id);
          }
          if (nodesByID.containsKey(id)) {
            throw BulkCommandException.of(
                BulkMessageKind.NODE_ALREADY_EXISTS,
                "Node " + id + " already exists",
                "nodeId",
                id);
          }
        }
      }
    }
    Map<String, SerializedClassifierInstance> requested = plan.requested;

    // Who contains what, according to the nodes in the request
    Map<String, String> claimedBy = new HashMap<>();
    for (SerializedClassifierInstance n : requested.values()) {
      List<String> contained = new ArrayList<>(n.getChildren());
      contained.addAll(n.getAnnotations());
      for (String id : contained) {
        if (!requested.containsKey(id) && !nodesByID.containsKey(id)) {
          throw parentMissing(
              id, "Node " + id + ", contained in " + n.getID() + ", is unknown (ParentMissing)");
        }
        String previous = claimedBy.put(id, n.getID());
        if (id.equals(n.getID())) {
          throw BulkCommandException.of(
              BulkMessageKind.TREE_CYCLE, "Node " + id + " contains itself", "nodeId", id);
        }
        if (previous != null) {
          if (previous.equals(n.getID())) {
            throw BulkCommandException.of(
                BulkMessageKind.NODE_LISTED_MORE_THAN_ONCE,
                "Node " + id + " is contained more than once in " + n.getID(),
                "nodeId",
                id,
                "parentNodeId",
                n.getID());
          }
          throw BulkCommandException.of(
              BulkMessageKind.NODE_HAS_MULTIPLE_PARENTS,
              "Node " + id + " is contained in both " + previous + " and " + n.getID(),
              "nodeId",
              id,
              "parentNodeId",
              previous,
              "otherParentNodeId",
              n.getID());
        }
      }
    }

    // Parents of the nodes in the request: they must exist and agree with the containers
    for (SerializedClassifierInstance n : requested.values()) {
      String id = n.getID();
      String parent = n.getParentNodeID();
      String claimant = claimedBy.get(id);
      if (parent == null) {
        if (claimant != null) {
          throw partitionOrMismatch(partitions.test(id), id, claimant, null);
        }
        if (!partitions.test(id)) {
          throw parentMissing(id, "Node " + id + " has no parent and is not a partition");
        }
        continue;
      }
      if (partitions.test(id)) {
        throw BulkCommandException.of(
            BulkMessageKind.PARTITION_HAS_PARENT,
            "Partition " + id + " has parent " + parent,
            "nodeId",
            id,
            "parentNodeId",
            parent);
      }
      if (!requested.containsKey(parent) && !nodesByID.containsKey(parent)) {
        throw parentMissing(id, "Parent " + parent + " of node " + id + " is unknown");
      }
      boolean parentListsIt =
          claimant != null
              ? claimant.equals(parent)
              : !requested.containsKey(parent) && isStoredChildOf(id, parent);
      if (!parentListsIt) {
        throw partitionOrMismatch(false, id, claimant, parent);
      }
    }

    // Nodes contained by nodes in the request, without being in the request, change parent
    for (Map.Entry<String, String> claim : claimedBy.entrySet()) {
      String id = claim.getKey();
      if (requested.containsKey(id)) {
        continue;
      }
      if (creatingPartitions) {
        throw BulkCommandException.of(
            BulkMessageKind.NODE_ALREADY_EXISTS,
            "Node " + id + " already exists and cannot be moved while creating partitions",
            "nodeId",
            id);
      }
      if (partitions.test(id)) {
        throw BulkCommandException.of(
            BulkMessageKind.PARTITION_HAS_PARENT,
            "Partition " + id + " cannot be contained in " + claim.getValue(),
            "nodeId",
            id,
            "parentNodeId",
            claim.getValue());
      }
      plan.implicitlyMovedTo.put(id, claim.getValue());
      String oldParent = nodesByID.get(id).getParentNodeID();
      if (oldParent != null && !oldParent.equals(claim.getValue())) {
        if (!requested.containsKey(oldParent) && nodesByID.containsKey(oldParent)) {
          plan.implicitlyRemovedFrom.add(new String[] {oldParent, id});
        }
      }
    }

    // Nodes dropped by a node in the request are deleted, unless they are moved elsewhere
    Deque<String> pending = new ArrayDeque<>();
    for (SerializedClassifierInstance n : requested.values()) {
      SerializedClassifierInstance current = nodesByID.get(n.getID());
      if (current == null) {
        continue;
      }
      Set<String> kept = new HashSet<>(n.getChildren());
      kept.addAll(n.getAnnotations());
      List<String> before = new ArrayList<>(current.getChildren());
      before.addAll(current.getAnnotations());
      for (String id : before) {
        if (!kept.contains(id) && !claimedBy.containsKey(id)) {
          pending.add(id);
        }
      }
    }
    while (!pending.isEmpty()) {
      String id = pending.poll();
      SerializedClassifierInstance node = nodesByID.get(id);
      if (node == null || claimedBy.containsKey(id) || !plan.removed.add(id)) {
        continue;
      }
      pending.addAll(node.getChildren());
      pending.addAll(node.getAnnotations());
    }

    // Every node must end up in a partition, without loops
    // walkOf: node -> index of the walk which visited it. A node visited by an earlier walk is
    // verified (a failing walk throws); meeting a node of the current walk again is a cycle.
    Map<String, Integer> walkOf = new HashMap<>();
    int walk = 0;
    for (Iterator<String> it =
            new ConcatIterator(
                requested.keySet().iterator(), plan.implicitlyMovedTo.keySet().iterator());
        it.hasNext(); ) {
      String start = it.next();
      Integer walkIndex = ++walk;
      String current = start;
      while (true) {
        Integer previous = walkOf.putIfAbsent(current, walkIndex);
        if (previous != null) {
          if (previous.equals(walkIndex)) {
            throw BulkCommandException.of(
                BulkMessageKind.TREE_CYCLE,
                "Node " + start + " would be among its own ancestors",
                "nodeId",
                start);
          }
          break;
        }
        if (plan.removed.contains(current)) {
          throw parentMissing(start, "Node " + start + " is placed under a deleted node");
        }
        String parent;
        if (claimedBy.containsKey(current)) {
          parent = claimedBy.get(current);
        } else if (requested.containsKey(current)) {
          parent = requested.get(current).getParentNodeID();
        } else {
          parent = nodesByID.get(current).getParentNodeID();
        }
        if (parent == null) {
          if (!partitions.test(current)) {
            throw parentMissing(current, "Node " + current + " is not contained in a partition");
          }
          break;
        }
        if (!requested.containsKey(parent) && !nodesByID.containsKey(parent)) {
          throw parentMissing(current, "Parent " + parent + " of node " + current + " is unknown");
        }
        current = parent;
      }
    }
    return plan;
  }

  private static final class ConcatIterator implements Iterator<String> {
    private final Iterator<String> first;
    private final Iterator<String> second;

    ConcatIterator(Iterator<String> first, Iterator<String> second) {
      this.first = first;
      this.second = second;
    }

    @Override
    public boolean hasNext() {
      return first.hasNext() || second.hasNext();
    }

    @Override
    public String next() {
      return first.hasNext() ? first.next() : second.next();
    }
  }

  /**
   * Whether the stored node {@code parent} contains {@code id}. As the repository keeps parent
   * pointers and containments in sync, a stored node pointing to {@code parent} is contained by it,
   * and the children of the parent do not need to be scanned.
   */
  private boolean isStoredChildOf(String id, String parent) {
    SerializedClassifierInstance stored = nodesByID.get(id);
    if (stored != null && parent.equals(stored.getParentNodeID())) {
      return true;
    }
    return nodesByID.get(parent).contains(id);
  }

  private static BulkCommandException partitionOrMismatch(
      boolean isPartition, String id, String container, String parent) {
    if (isPartition) {
      return BulkCommandException.of(
          BulkMessageKind.PARTITION_HAS_PARENT,
          "Partition " + id + " cannot be contained in " + container,
          "nodeId",
          id,
          "parentNodeId",
          container);
    }
    return BulkCommandException.of(
        BulkMessageKind.PARENT_MISMATCH,
        "Node "
            + id
            + " has parent "
            + parent
            + " but it is contained in "
            + container
            + " (a parent must contain its children)",
        "nodeId",
        id,
        "parentNodeId",
        String.valueOf(parent),
        "containerNodeId",
        String.valueOf(container));
  }

  /** Applies a validated plan. This is not expected to fail. */
  private void apply(StorePlan plan) {
    for (SerializedClassifierInstance updatedNode : plan.requested.values()) {
      SerializedClassifierInstance existing = nodesByID.get(updatedNode.getID());
      if (existing == null) {
        indexAdd(updatedNode);
      } else if (!classifierKeyOf(existing).equals(classifierKeyOf(updatedNode))) {
        indexRemove(existing);
        indexAdd(updatedNode);
      }
    }
    for (String[] oldParentAndChild : plan.implicitlyRemovedFrom) {
      removeContainedNode(oldParentAndChild[0], oldParentAndChild[1]);
    }
    plan.implicitlyMovedTo.forEach((id, parent) -> nodesByID.get(id).setParentNodeID(parent));
    nodesByID.putAll(plan.requested);
    partitionIDs.addAll(plan.newPartitions);
    plan.removed.forEach(this::removeFromIndexAndNodes);
  }

  /**
   * Retrieves the nodes with the given ids, down to the given depth. Unknown ids are reported in
   * the result instead of failing the command.
   *
   * @throws BulkCommandException if the depth limit is negative
   */
  RetrieveResult retrieveReportingUnknown(List<String> ids, int depthLimit) {
    if (depthLimit < 0) {
      throw BulkCommandException.of(
          BulkMessageKind.DEPTH_LIMIT_INCORRECT,
          "The depth limit must be >= 0, but it is " + depthLimit,
          "depthLimit",
          String.valueOf(depthLimit));
    }
    List<SerializedClassifierInstance> nodes = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (String id : ids) {
      if (nodesByID.containsKey(id)) {
        retrieve(id, depthLimit, nodes);
      } else {
        unknown.add(id);
      }
    }
    return new RetrieveResult(nodes, unknown);
  }

  List<SerializedClassifierInstance> retrieveTrees(List<String> ids) {
    List<SerializedClassifierInstance> nodes = new ArrayList<>();
    ids.forEach(n -> retrieveTree(n, nodes));
    return nodes;
  }

  private void retrieveTree(String id, List<SerializedClassifierInstance> nodes) {
    SerializedClassifierInstance n = nodesByID.get(id);
    nodes.add(n);
    n.getChildren().forEach(c -> retrieveTree(c, nodes));
  }

  void retrieve(String nodeId, int limit, List<SerializedClassifierInstance> retrieved) {
    SerializedClassifierInstance node = nodesByID.get(nodeId);
    if (node == null) {
      throw new IllegalArgumentException("Node with id " + nodeId + " cannot be found");
    }
    retrieved.add(node);
    if (limit > 0) {
      node.getChildren()
          .forEach(
              childId -> {
                try {
                  retrieve(childId, limit - 1, retrieved);
                } catch (Exception e) {
                  throw new RuntimeException("Unable to retrieve child of " + node, e);
                }
              });
      node.getAnnotations()
          .forEach(
              annotationId -> {
                try {
                  retrieve(annotationId, limit - 1, retrieved);
                } catch (Exception e) {
                  throw new RuntimeException("Unable to retrieve annotation of " + node, e);
                }
              });
    }
  }

  /** This is intended for debugging purposes. It checks if the data is consistent. */
  public @NotNull ValidationResult checkConsistency() {
    ValidationResult result = new ValidationResult();

    // Check for invalid node IDs
    for (String nodeId : nodesByID.keySet()) {
      if (!IdUtils.isValidID(nodeId)) {
        result.addError("Invalid node id: " + nodeId);
      }
    }

    // Check for duplicate node IDs (this is already guaranteed by using HashMap, but good to be
    // explicit)
    // No need for additional check since HashMap ensures uniqueness

    // Ensuring that containments and annotations are the inverse of parent relationships
    Map<String, Set<String>> containedNodes = new HashMap<>();
    for (SerializedClassifierInstance node : nodesByID.values()) {
      for (SerializedContainmentValue containmentValue : node.getContainments()) {
        for (String childId : containmentValue.getChildrenIds()) {
          // Verifying nodes do not appear in multiple containments or annotations
          String newPlacement = node.getID() + " at " + containmentValue.getMetaPointer();
          if (containedNodes.containsKey(childId)) {
            result.addError(
                childId
                    + " is listed in multiple places: "
                    + containedNodes.get(childId)
                    + " and now "
                    + newPlacement);
          } else {
            containedNodes.put(childId, new HashSet<>(Arrays.asList(newPlacement)));
          }
          SerializedClassifierInstance child = nodesByID.get(childId);
          if (child != null && !child.getParentNodeID().equals(node.getID())) {
            result.addError(
                childId
                    + " is listed as child of "
                    + node.getID()
                    + " but it has "
                    + child.getParentNodeID()
                    + " as parent");
          }
        }
      }
      for (String annotationId : node.getAnnotations()) {
        // Verifying nodes do not appear in multiple containments or annotations
        String newPlacement = node.getID() + " among annotations";
        if (containedNodes.containsKey(annotationId)) {
          result.addError(
              annotationId
                  + " is listed in multiple places: "
                  + containedNodes.get(annotationId)
                  + " and now "
                  + newPlacement);
        } else {
          containedNodes.put(annotationId, new HashSet<>(Arrays.asList(newPlacement)));
        }
        SerializedClassifierInstance annotation = nodesByID.get(annotationId);
        if (annotationId != null
            && annotation != null
            && !Objects.equals(annotation.getParentNodeID(), node.getID())) {
          result.addError(
              annotationId
                  + " is listed as an annotation of "
                  + node.getID()
                  + " but it has "
                  + annotation.getParentNodeID()
                  + "as parent ");
        }
      }

      if (node.getParentNodeID() != null) {
        SerializedClassifierInstance parent = nodesByID.get(node.getParentNodeID());
        if (parent != null && !parent.contains(node.getID())) {
          String msg =
              node.getID()
                  + " lists as parent "
                  + node.getParentNodeID()
                  + " but such parent does not contain it. It contains these children: "
                  + parent.getChildren().stream().collect(Collectors.joining(", "));
          if (!node.getAnnotations().isEmpty()) {
            msg +=
                " and these annotations: "
                    + node.getAnnotations().stream().collect(Collectors.joining(", "));
          }
          result.addError(msg);
        }
      }
    }

    return result;
  }
}
