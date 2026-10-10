package io.lionweb.client.delta;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.client.delta.messages.BaseDeltaEvent;
import io.lionweb.client.delta.messages.DeltaEvent;
import io.lionweb.client.delta.messages.DeltaQueryResponse;
import io.lionweb.client.delta.messages.events.StandardErrorCode;
import io.lionweb.client.delta.messages.events.partitions.PartitionAdded;
import io.lionweb.client.delta.messages.events.properties.PropertyChanged;
import io.lionweb.client.delta.messages.queries.ErrorResponse;
import io.lionweb.client.delta.messages.queries.ListAndSubscribePartitionsRequest;
import io.lionweb.client.delta.messages.queries.participations.ReconnectRequest;
import io.lionweb.client.delta.messages.queries.participations.ReconnectResponse;
import io.lionweb.client.delta.messages.queries.participations.SignOffRequest;
import io.lionweb.client.delta.messages.queries.participations.SignOnRequest;
import io.lionweb.client.delta.messages.queries.participations.SignOnResponse;
import io.lionweb.client.delta.messages.queries.subscriptions.SubscribeToPartitionContentsRequest;
import io.lionweb.client.delta.messages.queries.subscriptions.SubscribeToPartitionContentsResponse;
import io.lionweb.client.delta.messages.queries.subscriptions.UnsubscribeFromPartitionContentsRequest;
import io.lionweb.client.delta.messages.queries.subscriptions.UnsubscribeFromPartitionContentsResponse;
import io.lionweb.client.inmemory.InMemoryServer;
import io.lionweb.language.Language;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests the subscription and reconnection handling of the in-memory delta server. */
public class DeltaSubscriptionsTest extends AbstractDeltaProtocolTest {

  /**
   * A low-level participant, which sends queries directly on its own channel and records the events
   * it receives.
   */
  private static class RecordingParticipant implements DeltaEventReceiver {
    private final @NotNull DeltaChannel channel;
    private @Nullable String participationId;
    private final @NotNull List<DeltaEvent> events = new ArrayList<>();

    RecordingParticipant(@NotNull DeltaChannel channel) {
      this.channel = channel;
      channel.registerEventReceiver(this);
    }

    void signOn() {
      SignOnResponse response =
          (SignOnResponse)
              channel.sendQuery(
                  queryId -> new SignOnRequest(queryId, DeltaProtocolVersion.v2025_1, "recorder"));
      participationId = response.participationId;
    }

    @Override
    public void receiveEvent(@NotNull DeltaEvent event) {
      events.add(event);
    }

    @Override
    public @Nullable String getParticipationId() {
      return participationId;
    }
  }

  @Test
  public void eventsAreOnlySentToParticipationsSubscribedToThePartition() {
    InMemoryServer server = createServerWithRepository();
    Language langA = new Language("Language A", "lang-a", "lang-a-key");
    Language langB = new Language("Language B", "lang-b", "lang-b-key");
    server.createPartition("MyRepo", langA, serialization());
    server.createPartition("MyRepo", langB, serialization());

    DeltaClient client = new DeltaClient(prepareChannel(server), "my-client");
    client.sendSignOnRequest();
    client.monitorPartition(langA);

    RecordingParticipant recorder = new RecordingParticipant(prepareChannel(server));
    recorder.signOn();
    DeltaQueryResponse subscribeResponse =
        recorder.channel.sendQuery(
            queryId -> new SubscribeToPartitionContentsRequest(queryId, "lang-b"));
    assertInstanceOf(SubscribeToPartitionContentsResponse.class, subscribeResponse);

    // The recorder is not subscribed to lang-a, so it does not receive its events
    langA.setName("Language A1");
    assertEquals(0, recorder.events.size());

    // Once subscribed to lang-a, it receives its events
    recorder.channel.sendQuery(
        queryId -> new SubscribeToPartitionContentsRequest(queryId, "lang-a"));
    langA.setName("Language A2");
    assertEquals(1, recorder.events.size());
    PropertyChanged event = assertInstanceOf(PropertyChanged.class, recorder.events.get(0));
    assertEquals("lang-a", event.node);
    assertEquals("Language A2", event.newValue);

    // After unsubscribing, it does not receive them anymore
    DeltaQueryResponse unsubscribeResponse =
        recorder.channel.sendQuery(
            queryId -> new UnsubscribeFromPartitionContentsRequest(queryId, "lang-a"));
    assertInstanceOf(UnsubscribeFromPartitionContentsResponse.class, unsubscribeResponse);
    langA.setName("Language A3");
    assertEquals(1, recorder.events.size());
  }

  @Test
  public void subscribingRequiresAnActiveParticipation() {
    InMemoryServer server = createServerWithRepository();
    server.createPartition(
        "MyRepo", new Language("Language A", "lang-a", "lang-a-key"), serialization());
    DeltaChannel channel = prepareChannel(server);

    DeltaQueryResponse response =
        channel.sendQuery(queryId -> new SubscribeToPartitionContentsRequest(queryId, "lang-a"));
    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(StandardErrorCode.INVALID_PARTICIPATION.code, error.errorCode);

    response = channel.sendQuery(ListAndSubscribePartitionsRequest::new);
    error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(StandardErrorCode.INVALID_PARTICIPATION.code, error.errorCode);
  }

  @Test
  public void subscribingToAnUnknownPartitionIsRejected() {
    InMemoryServer server = createServerWithRepository();
    RecordingParticipant recorder = new RecordingParticipant(prepareChannel(server));
    recorder.signOn();

    DeltaQueryResponse response =
        recorder.channel.sendQuery(
            queryId -> new SubscribeToPartitionContentsRequest(queryId, "unknown"));
    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(StandardErrorCode.UNKNOWN_NODE.code, error.errorCode);
  }

  @Test
  public void partitionEventsAreOnlySentToParticipationsSubscribedToThePartitionList() {
    InMemoryServer server = createServerWithRepository();

    DeltaClient client = new DeltaClient(prepareChannel(server), "my-client");
    client.sendSignOnRequest();

    RecordingParticipant subscribed = new RecordingParticipant(prepareChannel(server));
    subscribed.signOn();
    subscribed.channel.sendQuery(ListAndSubscribePartitionsRequest::new);

    RecordingParticipant notSubscribed = new RecordingParticipant(prepareChannel(server));
    notSubscribed.signOn();

    client.sendAddPartitionCommand(new Language("New Language", "new-lang", "new-lang-key"));

    assertEquals(1, subscribed.events.size());
    assertInstanceOf(PartitionAdded.class, subscribed.events.get(0));
    assertEquals(0, notSubscribed.events.size());
  }

  @Test
  public void creatingAPartitionSubscribesToIt() {
    InMemoryServer server = createServerWithRepository();

    RecordingParticipant creator = new RecordingParticipant(prepareChannel(server));
    creator.signOn();
    Language language = new Language("New Language", "new-lang", "new-lang-key");
    DeltaClient creatorClient = new DeltaClient(creator.channel, "creator");
    // Bind the client to the same participation as the recorder
    creatorClient.sendReconnectRequest(creator.participationId, 0);
    creatorClient.sendAddPartitionCommand(language);
    int eventsAfterCreation = creator.events.size();

    DeltaClient other = new DeltaClient(prepareChannel(server), "other");
    other.sendSignOnRequest();
    Language otherCopy =
        (Language) server.retrieveAsClassifierInstance("MyRepo", "new-lang", serialization());
    other.monitorPartition(otherCopy);
    otherCopy.setName("Renamed");

    assertEquals(eventsAfterCreation + 1, creator.events.size());
    assertInstanceOf(PropertyChanged.class, creator.events.get(eventsAfterCreation));
  }

  @Test
  public void reconnectReturnsTheLastSentSequenceNumber() {
    InMemoryServer server = createServerWithRepository();
    Language language = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", language, serialization());

    DeltaClient client = new DeltaClient(prepareChannel(server), "my-client");
    client.sendSignOnRequest();
    client.monitorPartition(language);

    RecordingParticipant recorder = new RecordingParticipant(prepareChannel(server));
    recorder.signOn();
    recorder.channel.sendQuery(
        queryId -> new SubscribeToPartitionContentsRequest(queryId, "lang-a"));

    language.setName("Language B");
    language.setName("Language C");
    language.setName("Language D");
    assertEquals(3, recorder.events.size());
    int lastSequenceNumber =
        ((BaseDeltaEvent<?>) recorder.events.get(recorder.events.size() - 1)).sequenceNumber;
    assertTrue(lastSequenceNumber > 0);

    // Reconnect the same participation on a new channel
    DeltaChannel newChannel = prepareChannel(server);
    DeltaQueryResponse response =
        newChannel.sendQuery(queryId -> new ReconnectRequest(queryId, recorder.participationId, 1));
    ReconnectResponse reconnectResponse = assertInstanceOf(ReconnectResponse.class, response);
    assertEquals(lastSequenceNumber, reconnectResponse.lastSentSequenceNumber);
  }

  @Test
  public void reconnectWithoutEventsReturnsZero() {
    InMemoryServer server = createServerWithRepository();
    RecordingParticipant recorder = new RecordingParticipant(prepareChannel(server));
    recorder.signOn();

    DeltaQueryResponse response =
        prepareChannel(server)
            .sendQuery(queryId -> new ReconnectRequest(queryId, recorder.participationId, 0));
    ReconnectResponse reconnectResponse = assertInstanceOf(ReconnectResponse.class, response);
    assertEquals(0, reconnectResponse.lastSentSequenceNumber);
  }

  @Test
  public void reconnectAfterSignOffIsRejected() {
    InMemoryServer server = createServerWithRepository();
    RecordingParticipant recorder = new RecordingParticipant(prepareChannel(server));
    recorder.signOn();
    recorder.channel.sendQuery(SignOffRequest::new);

    DeltaClient client = new DeltaClient(prepareChannel(server), "my-client");
    ErrorEventReceivedException e =
        assertThrows(
            ErrorEventReceivedException.class,
            () -> client.sendReconnectRequest(recorder.participationId, 0));
    assertEquals(StandardErrorCode.INVALID_PARTICIPATION.code, e.getCode());
    assertNull(client.getParticipationId());
  }
}
