package io.lionweb.client.inmemory;

import io.lionweb.LionWebVersion;
import io.lionweb.client.delta.DeltaQueryReceiver;
import io.lionweb.client.delta.messages.DeltaQuery;
import io.lionweb.client.delta.messages.DeltaQueryResponse;
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
        ErrorResponse error = new ErrorResponse(reconnectRequest.queryId);
        error.errorCode = StandardErrorCode.INVALID_PARTICIPATION.code;
        error.message = "Unknown participation: " + reconnectRequest.participationId;
        return error;
      }
      currentParticipationId = reconnectRequest.participationId;
      return new ReconnectResponse(reconnectRequest.queryId, 0);
    } else if (query instanceof ListPartitionsRequest) {
      ListPartitionsRequest req = (ListPartitionsRequest) query;
      RepositoryData repositoryData = inMemoryServer.getRepository(repositoryName);
      SerializationChunk chunk = buildPartitionRootsChunk(repositoryData);
      return new ListPartitionsResponse(req.queryId, chunk);
    } else if (query instanceof ListAndSubscribePartitionsRequest) {
      ListAndSubscribePartitionsRequest req = (ListAndSubscribePartitionsRequest) query;
      RepositoryData repositoryData = inMemoryServer.getRepository(repositoryName);
      SerializationChunk chunk = buildPartitionRootsChunk(repositoryData);
      return new ListAndSubscribePartitionsResponse(req.queryId, chunk, false);
    } else if (query instanceof SubscribeToPartitionContentsRequest) {
      SubscribeToPartitionContentsRequest req = (SubscribeToPartitionContentsRequest) query;
      RepositoryData repositoryData = inMemoryServer.getRepository(repositoryName);
      List<SerializedClassifierInstance> nodes = new ArrayList<>();
      repositoryData.retrieve(req.partition, Integer.MAX_VALUE, nodes);
      LionWebVersion version = repositoryData.configuration.getLionWebVersion();
      SerializationChunk chunk = SerializationChunk.fromNodes(version, nodes);
      return new SubscribeToPartitionContentsResponse(req.queryId, chunk);
    } else if (query instanceof UnsubscribeFromPartitionContentsRequest) {
      UnsubscribeFromPartitionContentsRequest req = (UnsubscribeFromPartitionContentsRequest) query;
      return new UnsubscribeFromPartitionContentsResponse(req.queryId);
    }
    ErrorResponse error = new ErrorResponse(query.queryId);
    error.errorCode = StandardErrorCode.MESSAGE_KIND_UNKNOWN.code;
    error.message = "Unsupported query type: " + query.getClass().getName();
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
