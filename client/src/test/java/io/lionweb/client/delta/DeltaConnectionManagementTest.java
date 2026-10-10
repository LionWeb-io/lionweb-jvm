package io.lionweb.client.delta;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.lionweb.LionWebVersion;
import io.lionweb.client.delta.messages.events.StandardErrorCode;
import io.lionweb.client.inmemory.InMemoryServer;
import io.lionweb.language.Language;
import io.lionweb.serialization.JsonSerialization;
import io.lionweb.serialization.SerializationProvider;
import org.junit.jupiter.api.Test;

public class DeltaConnectionManagementTest extends AbstractDeltaProtocolTest {

  /**
   * Scenario Start participation.
   *
   * <p>A client sends a SignOnRequest and receives a SignOnResponse with a non-null
   * participationId.
   */
  @Test
  public void startParticipation() {
    InMemoryServer server = createServerWithRepository();
    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = new DeltaClient(channel, "my-client-1");
    assertNull(client.getParticipationId());
    assertEquals(DeltaClient.ParticipationState.NOT_CONNECTED, client.getState());

    client.sendSignOnRequest();

    assertNotNull(client.getParticipationId());
    assertEquals(DeltaClient.ParticipationState.CONNECTED, client.getState());
  }

  /**
   * Scenario Reconnect participation.
   *
   * <p>A client signs on, then a second DeltaClient (simulating a reconnected session) resumes the
   * same participation via ReconnectRequest. After reconnect the participationId is correctly set.
   */
  @Test
  public void reconnectParticipation() {
    InMemoryServer server = createServerWithRepository();
    DeltaChannel channel = prepareChannel(server);

    // Original session: sign on
    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();
    String participationId = client1.getParticipationId();
    assertNotNull(participationId);

    // Simulate transport failure: create a new DeltaClient, on a new channel, that does NOT sign on
    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-1");
    assertNull(client2.getParticipationId());

    // Reconnect using the previously issued participationId
    client2.sendReconnectRequest(participationId, 0);

    assertEquals(participationId, client2.getParticipationId());
  }

  /**
   * Scenario: End participation.
   *
   * <p>A client signs on, performs a change, then signs off. After sign-off the server rejects
   * further commands from that participation with an INVALID_PARTICIPATION error.
   */
  @Test
  public void endParticipation() {
    InMemoryServer server = createServerWithRepository();

    JsonSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1);
    Language language = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", language, serialization);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = new DeltaClient(channel, "my-client-1");
    client.sendSignOnRequest();
    client.monitorPartition(language);

    // Normal operation works before sign-off
    language.setName("Language B");
    assertEquals("Language B", language.getName());

    // Sign off
    client.sendSignOffRequest();
    assertEquals(DeltaClient.ParticipationState.SIGNED_OFF, client.getState());

    // Commands after sign-off are rejected with INVALID_PARTICIPATION
    try {
      language.setName("Language C");
      fail("Expected ErrorEventReceivedException after sign-off");
    } catch (ErrorEventReceivedException e) {
      assertEquals(StandardErrorCode.INVALID_PARTICIPATION.code, e.getCode());
    }
  }
}
