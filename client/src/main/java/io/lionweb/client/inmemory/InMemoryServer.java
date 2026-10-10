package io.lionweb.client.inmemory;

import io.lionweb.LionWebVersion;
import io.lionweb.client.api.*;
import io.lionweb.client.delta.DeltaChannel;
import io.lionweb.client.delta.messages.DeltaEvent;
import io.lionweb.model.ClassifierInstance;
import io.lionweb.model.Node;
import io.lionweb.serialization.AbstractSerialization;
import io.lionweb.serialization.data.SerializationChunk;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import io.lionweb.utils.ValidationResult;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An InMemoryServer is useful for testing and as a replacement for a proper server, when performing
 * processing operations.
 *
 * <p>We store data using SerializedClassifierInstance so that: - We do not need to know the
 * languages - We can inspect the nodes while we could not if we stored the data serialized in JSON
 * or binary formats.
 *
 * <p>Different clients can then still work with nodes or JSON or binary formats.
 *
 * <p>Also look at {@link NodesLevelInMemoryServerClient} for easier handling of node storage and
 * retrieval.
 */
public class InMemoryServer {

  /** Internally we store the data separately for each repository. */
  private final Map<String, RepositoryData> repositories = new ConcurrentHashMap<>();

  private final boolean materializeClassifierIndex;

  private final ParticipationManager participationManager = new ParticipationManager();

  /** The delta channels monitored for each repository. */
  private final Map<String, Set<DeltaChannel>> deltaChannels = new ConcurrentHashMap<>();

  public InMemoryServer() {
    this(true);
  }

  public InMemoryServer(boolean materializeClassifierIndex) {
    this.materializeClassifierIndex = materializeClassifierIndex;
  }

  public @NotNull RepositoryConfiguration getRepositoryConfiguration(
      @NotNull String repositoryName) {
    return getRepository(repositoryName).configuration;
  }

  public @NotNull List<String> ids(@NotNull String repositoryName, int count) {
    if (count < 0) {
      throw new IllegalArgumentException("One can ask for zero or more ids");
    }
    RepositoryData repositoryData = getRepository(repositoryName);
    return repositoryData.ids(count);
  }

  public @NotNull Set<RepositoryConfiguration> listRepositories() {
    return repositories.values().stream().map(r -> r.configuration).collect(Collectors.toSet());
  }

  public void createRepository(@NotNull RepositoryConfiguration repositoryConfiguration) {
    Objects.requireNonNull(repositoryConfiguration, "repositoryConfiguration should not be null");
    if (repositoryConfiguration.getHistorySupport() == HistorySupport.ENABLED) {
      throw new IllegalArgumentException(
          "The InMemoryServer does not support History for the time being");
    }
    repositories.put(
        repositoryConfiguration.getName(),
        new RepositoryData(repositoryConfiguration, materializeClassifierIndex));
  }

  public void deleteRepository(@NotNull String repositoryName) {
    Objects.requireNonNull(repositoryName, "repositoryName should not be null");
    if (!repositories.containsKey(repositoryName)) {
      throw new IllegalArgumentException();
    }
    repositories.remove(repositoryName);
    deltaChannels.remove(repositoryName);
  }

  public @NotNull List<String> listPartitionIDs(@NotNull String repositoryName) {
    Objects.requireNonNull(repositoryName, "RepositoryName should not be null");
    RepositoryData repositoryData = repositories.get(repositoryName);
    return repositoryData.partitionIDs;
  }

  public @NotNull RepositoryVersionToken createPartitionFromChunk(
      @NotNull String repositoryName, @NotNull List<SerializedClassifierInstance> partitions) {
    Objects.requireNonNull(partitions, "partitions should not be null");
    RepositoryData repositoryData = getRepository(repositoryName);
    // We get all roots (i.e. -> partitions) which do not yet exist
    // and add them to the list of partition IDs
    repositoryData.partitionIDs.addAll(
        partitions.stream()
            .filter(n -> n.getParentNodeID() == null)
            .map(SerializedClassifierInstance::getID)
            .filter(id -> !repositoryData.partitionIDs.contains(id))
            .collect(Collectors.toList()));
    repositoryData.store(partitions);
    return repositoryData.bumpVersion();
  }

  public @NotNull RepositoryVersionToken createPartition(
      @NotNull String repositoryName,
      @NotNull Node partition,
      @NotNull AbstractSerialization serialization) {
    Objects.requireNonNull(repositoryName, "RepositoryName should not be null");
    Objects.requireNonNull(partition, "Partition should not be null");
    Objects.requireNonNull(serialization, "Serialization should not be null");
    if (partition.getParent() != null) {
      throw new IllegalArgumentException("Partition should not have a parent");
    }

    SerializationChunk serializationChunk =
        serialization.serializeNodesToSerializationChunk(partition);
    return createPartitionFromChunk(repositoryName, serializationChunk.getClassifierInstances());
  }

  public @NotNull RepositoryVersionToken deletePartitions(
      @NotNull String repositoryName, @NotNull List<String> partitionIds) {
    Objects.requireNonNull(partitionIds, "partitionIds should not be null");
    RepositoryData repositoryData = getRepository(repositoryName);
    repositoryData.partitionIDs.removeIf(partitionIds::contains);
    partitionIds.forEach(repositoryData::deleteNodeAndDescendant);
    return repositoryData.bumpVersion();
  }

  public List<SerializedClassifierInstance> retrieve(
      @NotNull String repositoryName, List<String> nodeIds, int limit) {
    Objects.requireNonNull(repositoryName, "RepositoryName should not be null");
    RepositoryData repositoryData = repositories.get(repositoryName);
    List<SerializedClassifierInstance> retrieved = new ArrayList<>();
    nodeIds.forEach(n -> repositoryData.retrieve(n, limit, retrieved));
    return retrieved;
  }

  public @Nullable ClassifierInstance<?> retrieveAsClassifierInstance(
      @NotNull String repositoryName,
      @NotNull String nodeId,
      @NotNull AbstractSerialization serialization) {
    Objects.requireNonNull(repositoryName, "RepositoryName should not be null");
    Objects.requireNonNull(nodeId, "NodeId should not be null");
    Objects.requireNonNull(serialization, "Serialization should not be null");
    List<SerializedClassifierInstance> serializedNodes =
        retrieve(repositoryName, List.of(nodeId), 1);
    if (serializedNodes.isEmpty()) {
      return null;
    }
    LionWebVersion lionWebVersion =
        repositories.get(repositoryName).configuration.getLionWebVersion();
    List<ClassifierInstance<?>> nodes =
        serialization.deserializeSerializationChunk(
            SerializationChunk.fromNodes(lionWebVersion, serializedNodes));
    return nodes.stream().filter(n -> Objects.equals(n.getID(), nodeId)).findFirst().orElse(null);
  }

  /**
   * @param nodes {@link io.lionweb.serialization.LowLevelJsonSerialization} can produce {@link
   *     SerializedClassifierInstance} nodes, if we need to store data from JSON files.
   */
  public RepositoryVersionToken store(
      @NotNull String repositoryName, @NotNull List<SerializedClassifierInstance> nodes) {
    Objects.requireNonNull(repositoryName, "RepositoryName should not be null");
    RepositoryData repositoryData = repositories.get(repositoryName);
    repositoryData.store(nodes);
    return repositoryData.bumpVersion();
  }

  //
  // Inspection
  //

  public Map<ClassifierKey, ClassifierResult> nodesByClassifier(@NotNull String repositoryName) {
    return nodesByClassifier(repositoryName, Integer.MAX_VALUE);
  }

  public ClassifierResult nodesByClassifier(@NotNull String repositoryName, ClassifierKey key) {
    return nodesByClassifier(repositoryName, Integer.MAX_VALUE, key);
  }

  public Map<ClassifierKey, ClassifierResult> nodesByClassifier(
      @NotNull String repositoryName, @Nullable Integer limit) {
    RepositoryData repositoryData = getRepository(repositoryName);
    return repositoryData.nodesByClassifier(limit);
  }

  public ClassifierResult nodesByClassifier(
      @NotNull String repositoryName, @Nullable Integer limit, ClassifierKey key) {
    RepositoryData repositoryData = getRepository(repositoryName);
    return repositoryData.nodesByClassifier(limit, key);
  }

  public Map<String, ClassifierResult> nodesByLanguage(@NotNull String repositoryName) {
    return nodesByLanguage(repositoryName, Integer.MAX_VALUE);
  }

  public Map<String, ClassifierResult> nodesByLanguage(
      @NotNull String repositoryName, @Nullable Integer limit) {
    int actualLimit = (limit != null) ? limit : Integer.MAX_VALUE;
    RepositoryData repositoryData = getRepository(repositoryName);
    Map<String, List<SerializedClassifierInstance>> byMetapointer =
        repositoryData.nodesByID.values().stream()
            .collect(Collectors.groupingBy(n -> n.getClassifier().getLanguage()));
    Map<String, ClassifierResult> res = new HashMap<>();
    for (Map.Entry<String, List<SerializedClassifierInstance>> entry : byMetapointer.entrySet()) {
      ClassifierResult cr =
          new ClassifierResult(
              entry.getValue().stream()
                  .limit(actualLimit)
                  .map(n -> n.getID())
                  .collect(Collectors.toSet()),
              entry.getValue().size());
      res.put(entry.getKey(), cr);
    }
    return res;
  }

  /**
   * Checks the consistency of all repositories stored in the system and aggregates any validation
   * issues found into a single {@link ValidationResult}.
   *
   * <p>The method iterates through all repository data, invokes their individual consistency
   * checks, and collects any issues reported into the resulting validation result object.
   *
   * <p>This is intended for debugging purposes.
   *
   * @return a {@link ValidationResult} containing all identified issues, or an empty result if no
   *     issues were found.
   */
  public @NotNull ValidationResult checkConsistency() {
    ValidationResult result = new ValidationResult();
    for (RepositoryData repositoryData : repositories.values()) {
      ValidationResult partial = repositoryData.checkConsistency();
      result.getIssues().addAll(partial.getIssues());
    }
    return result;
  }

  //
  // Delta methods
  //

  /**
   * Monitors the specified {@link DeltaChannel} to handle command and query operations for a given
   * repository.
   *
   * <p>This method sets up the provided DeltaChannel by registering command and query receivers
   * that are tailored to the context of the specified repository. The command receiver is
   * responsible for processing client-initiated commands, while the query receiver handles queries
   * requesting information from the repository.
   *
   * <p>The server keeps track, for each participation, of the partitions it is subscribed to
   * (through {@code SubscribeToPartitionContentsRequest}, {@code
   * UnsubscribeFromPartitionContentsRequest}, and by creating a partition), and of whether it is
   * subscribed to changes to the list of partitions (through {@code
   * ListAndSubscribePartitionsRequest}). Events concerning the contents of a partition are sent
   * only to the participations subscribed to that partition, while events concerning the creation
   * or deletion of partitions are also sent to the participations subscribed to the list of
   * partitions. Error events are sent only to the participation that issued the failing command.
   * Events are delivered to participations connected through any of the channels monitored for the
   * same repository. The server also tracks the sequence number of the last event sent to each
   * participation, and reports it in {@code ReconnectResponse}.
   *
   * <p>Subscriptions are attributed to the participation bound to the channel (the last one that
   * signed on or reconnected through it), so each participation should use its own channel. Events
   * are delivered to specific participations only through channels supporting it (see {@link
   * DeltaChannel#supportsTargetedEvents()}), such as {@link
   * io.lionweb.client.delta.InMemoryDeltaChannel}. Other channels receive each event once and are
   * responsible for routing it to the interested participations.
   *
   * @param repositoryName the name of the repository associated with the DeltaChannel
   * @param channel the DeltaChannel to monitor; must not be null
   * @throws NullPointerException if the specified channel is null
   */
  public void monitorDeltaChannel(@NotNull String repositoryName, @NotNull DeltaChannel channel) {
    Objects.requireNonNull(repositoryName, "RepositoryName should not be null");
    Objects.requireNonNull(channel, "Channel should not be null");
    deltaChannels.computeIfAbsent(repositoryName, k -> new CopyOnWriteArraySet<>()).add(channel);
    channel.registerCommandReceiver(
        new DeltaCommandReceiverImpl(repositoryName, channel, participationManager, this));
    channel.registerQueryReceiver(
        new DeltaQueryReceiverImpl(repositoryName, participationManager, this));
  }

  //
  // Package-protected methods
  //

  /**
   * Sends an event to the given participations, through all the channels monitored for the given
   * repository, keeping track of the sequence number of the last event sent to each participation.
   * Channels not supporting targeted events receive the event once, and are responsible for routing
   * it.
   */
  void sendEvent(
      @NotNull String repositoryName,
      @NotNull Collection<String> participationIds,
      @NotNull Function<Integer, DeltaEvent> eventProducer) {
    Set<DeltaChannel> channels = deltaChannels.getOrDefault(repositoryName, Collections.emptySet());
    for (DeltaChannel channel : channels) {
      sendEvent(channel, participationIds, eventProducer);
    }
  }

  /**
   * Sends an event to the given participations, through the given channel, keeping track of the
   * sequence number of the last event sent to each participation. If the channel does not support
   * targeted events, the event is sent once and it is up to the channel to route it.
   */
  void sendEvent(
      @NotNull DeltaChannel channel,
      @NotNull Collection<String> participationIds,
      @NotNull Function<Integer, DeltaEvent> eventProducer) {
    if (channel.supportsTargetedEvents()) {
      for (String participationId : participationIds) {
        channel.sendEvent(
            participationId,
            sequenceNumber -> {
              participationManager.recordSentEvent(participationId, sequenceNumber);
              return eventProducer.apply(sequenceNumber);
            });
      }
    } else {
      channel.sendEvent(
          sequenceNumber -> {
            participationIds.forEach(
                participationId ->
                    participationManager.recordSentEvent(participationId, sequenceNumber));
            return eventProducer.apply(sequenceNumber);
          });
    }
  }

  @NotNull
  RepositoryData getRepository(@NotNull String repositoryName) {
    Objects.requireNonNull(repositoryName, "RepositoryName should not be null");
    RepositoryData repositoryData = repositories.get(repositoryName);
    if (repositoryData == null) {
      throw new IllegalArgumentException("Cannot find repository named " + repositoryName);
    }
    return repositoryData;
  }
}
