package io.lionweb.client.inmemory;

import io.lionweb.LionWebVersion;
import io.lionweb.client.delta.DeltaQueryReceiver;
import io.lionweb.client.delta.messages.DeltaQuery;
import io.lionweb.client.delta.messages.DeltaQueryResponse;
import io.lionweb.client.delta.messages.events.CustomErrorCode;
import io.lionweb.client.delta.messages.events.StandardErrorCode;
import io.lionweb.client.delta.messages.queries.*;
import io.lionweb.client.delta.messages.queries.participations.*;
import io.lionweb.client.delta.messages.queries.subscriptions.SubscribeToPartitionContentsRequest;
import io.lionweb.client.delta.messages.queries.subscriptions.SubscribeToPartitionContentsResponse;
import io.lionweb.client.delta.messages.queries.subscriptions.UnsubscribeFromPartitionContentsRequest;
import io.lionweb.client.delta.messages.queries.subscriptions.UnsubscribeFromPartitionContentsResponse;
import io.lionweb.serialization.data.SerializationChunk;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class DeltaQueryReceiverImpl implements DeltaQueryReceiver {

  private final @NotNull String repositoryName;
  private final @NotNull ParticipationManager participationManager;
  private final @NotNull InMemoryServer inMemoryServer;

  /** The participationId currently bound to this channel session; null before sign-on. */
  private String currentParticipationId;

  DeltaQueryReceiverImpl(
      @NotNull String repositoryName,
      @NotNull ParticipationManager participationManager,
      @NotNull InMemoryServer inMemoryServer) {
    this.repositoryName = repositoryName;
    this.participationManager = participationManager;
    this.inMemoryServer = inMemoryServer;
  }

  @NotNull
  @Override
  public DeltaQueryResponse receiveQuery(@NotNull DeltaQuery query) {
    if (query instanceof SignOnRequest) {
      SignOnRequest signOnRequest = (SignOnRequest) query;
      currentParticipationId = participationManager.createParticipationId();
      return new SignOnResponse(signOnRequest.queryId, currentParticipationId);
    } else if (query instanceof SignOffRequest) {
      SignOffRequest signOffRequest = (SignOffRequest) query;
      participationManager.drop(currentParticipationId);
      currentParticipationId = null;
      return new SignOffResponse(signOffRequest.queryId);
    } else if (query instanceof ReconnectRequest) {
      ReconnectRequest reconnectRequest = (ReconnectRequest) query;
      if (!participationManager.isActiveParticipation(reconnectRequest.participationId)) {
        return new ErrorResponse(
            reconnectRequest.queryId,
            StandardErrorCode.INVALID_PARTICIPATION,
            "Unknown participation: " + reconnectRequest.participationId);
      }
      currentParticipationId = reconnectRequest.participationId;
      return new ReconnectResponse(
          reconnectRequest.queryId,
          participationManager.getLastSentSequenceNumber(currentParticipationId));
    } else if (query instanceof ListPartitionsRequest) {
      ListPartitionsRequest req = (ListPartitionsRequest) query;
      RepositoryData repositoryData = inMemoryServer.getRepository(repositoryName);
      SerializationChunk chunk = buildPartitionRootsChunk(repositoryData);
      return new ListPartitionsResponse(req.queryId, chunk);
    } else if (query instanceof ListAndSubscribePartitionsRequest) {
      ListAndSubscribePartitionsRequest req = (ListAndSubscribePartitionsRequest) query;
      ErrorResponse error = checkParticipation(req.queryId);
      if (error != null) return error;
      participationManager.subscribeToPartitionList(currentParticipationId);
      RepositoryData repositoryData = inMemoryServer.getRepository(repositoryName);
      SerializationChunk chunk = buildPartitionRootsChunk(repositoryData);
      // The in-memory server never splits the response, so split is always false
      return new ListAndSubscribePartitionsResponse(req.queryId, chunk, false);
    } else if (query instanceof SubscribeToPartitionContentsRequest) {
      SubscribeToPartitionContentsRequest req = (SubscribeToPartitionContentsRequest) query;
      ErrorResponse error = checkParticipation(req.queryId);
      if (error != null) return error;
      RepositoryData repositoryData = inMemoryServer.getRepository(repositoryName);
      if (!repositoryData.partitionIDs.contains(req.partition)) {
        error = new ErrorResponse(req.queryId);
        error.errorCode = StandardErrorCode.UNKNOWN_NODE.code;
        error.message = "Unknown partition: " + req.partition;
        return error;
      }
      List<SerializedClassifierInstance> nodes = new ArrayList<>();
      repositoryData.retrieve(req.partition, Integer.MAX_VALUE, nodes);
      participationManager.subscribeToPartition(currentParticipationId, req.partition);
      LionWebVersion version = repositoryData.configuration.getLionWebVersion();
      SerializationChunk chunk = SerializationChunk.fromNodes(version, nodes);
      return new SubscribeToPartitionContentsResponse(req.queryId, chunk);
    } else if (query instanceof UnsubscribeFromPartitionContentsRequest) {
      UnsubscribeFromPartitionContentsRequest req = (UnsubscribeFromPartitionContentsRequest) query;
      ErrorResponse error = checkParticipation(req.queryId);
      if (error != null) return error;
      participationManager.unsubscribeFromPartition(currentParticipationId, req.partition);
      return new UnsubscribeFromPartitionContentsResponse(req.queryId);
    }
    return notImplemented(query);
  }

  /** Builds the error response for a recognized query that this server does not implement. */
  private static @NotNull ErrorResponse notImplemented(@NotNull DeltaQuery query) {
    return new ErrorResponse(
        query.queryId,
        CustomErrorCode.NOT_IMPLEMENTED,
        "Unsupported query type: " + query.getClass().getName());
  }

  /**
   * Returns an error response if this channel session is not bound to an active participation, null
   * otherwise.
   */
  private @Nullable ErrorResponse checkParticipation(@NotNull String queryId) {
    if (currentParticipationId != null
        && participationManager.isActiveParticipation(currentParticipationId)) {
      return null;
    }
    ErrorResponse error = new ErrorResponse(queryId);
    error.errorCode = StandardErrorCode.INVALID_PARTICIPATION.code;
    error.message = "No active participation: sign on or reconnect first";
    return error;
  }

  private @NotNull SerializationChunk buildPartitionRootsChunk(
      @NotNull RepositoryData repositoryData) {
    List<SerializedClassifierInstance> roots =
        repositoryData.partitionIDs.stream()
            .map(id -> repositoryData.nodesByID.get(id))
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());
    if (roots.isEmpty()) {
      SerializationChunk empty = new SerializationChunk();
      empty.setSerializationFormatVersion(
          repositoryData.configuration.getLionWebVersion().getVersionString());
      return empty;
    }
    return SerializationChunk.fromNodes(repositoryData.configuration.getLionWebVersion(), roots);
  }
}
