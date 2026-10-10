package io.lionweb.client.delta;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.client.delta.messages.DeltaEvent;
import io.lionweb.client.delta.messages.DeltaQueryResponse;
import io.lionweb.client.delta.messages.commands.CompositeCommand;
import io.lionweb.client.delta.messages.events.ErrorEvent;
import io.lionweb.client.delta.messages.events.StandardErrorCode;
import io.lionweb.client.delta.messages.queries.ErrorResponse;
import io.lionweb.client.delta.messages.queries.GetAvailableIdsRequest;
import io.lionweb.client.delta.messages.queries.ListPartitionsRequest;
import io.lionweb.client.delta.messages.queries.ListPartitionsResponse;
import io.lionweb.client.delta.messages.queries.participations.SignOnRequest;
import io.lionweb.client.delta.messages.queries.participations.SignOnResponse;
import io.lionweb.client.delta.messages.queries.subscriptions.SubscribeToChangingPartitionsRequest;
import io.lionweb.client.inmemory.InMemoryServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the in-memory delta server answers queries and commands it does not support with a
 * protocol error instead of throwing, and keeps working afterward.
 */
public class DeltaUnsupportedMessagesTest extends AbstractDeltaProtocolTest {

  @Test
  public void unsupportedQueryReturnsErrorResponse() {
    InMemoryServer server = createServerWithRepository();
    DeltaChannel channel = prepareChannel(server);

    DeltaQueryResponse response =
        channel.sendQuery(queryId -> new GetAvailableIdsRequest(queryId, 3));
    assertInstanceOf(ErrorResponse.class, response);
    ErrorResponse error = (ErrorResponse) response;
    assertEquals(StandardErrorCode.MESSAGE_KIND_UNKNOWN.code, error.errorCode);
    assertNotNull(error.message);
    assertTrue(error.message.contains(GetAvailableIdsRequest.class.getName()));

    response =
        channel.sendQuery(queryId -> new SubscribeToChangingPartitionsRequest(queryId, true, true));
    assertInstanceOf(ErrorResponse.class, response);
    assertEquals(StandardErrorCode.MESSAGE_KIND_UNKNOWN.code, ((ErrorResponse) response).errorCode);

    // The server keeps working after the unsupported queries
    assertInstanceOf(ListPartitionsResponse.class, channel.sendQuery(ListPartitionsRequest::new));
  }

  @Test
  public void unsupportedCommandSendsErrorEvent() {
    InMemoryServer server = createServerWithRepository();
    DeltaChannel channel = prepareChannel(server);

    List<DeltaEvent> events = new ArrayList<>();
    channel.registerEventReceiver(events::add);

    DeltaQueryResponse signOn =
        channel.sendQuery(
            queryId -> new SignOnRequest(queryId, DeltaProtocolVersion.v2026_1, "my-client-1"));
    String participationId = ((SignOnResponse) signOn).participationId;

    channel.sendCommand(
        participationId, commandId -> new CompositeCommand(commandId, Collections.emptyList()));

    assertEquals(1, events.size());
    assertInstanceOf(ErrorEvent.class, events.get(0));
    ErrorEvent errorEvent = (ErrorEvent) events.get(0);
    assertEquals(StandardErrorCode.MESSAGE_KIND_UNKNOWN.code, errorEvent.errorCode);
    assertNotNull(errorEvent.message);
    assertTrue(errorEvent.message.contains(CompositeCommand.class.getName()));

    // The server keeps working after the unsupported command
    assertInstanceOf(ListPartitionsResponse.class, channel.sendQuery(ListPartitionsRequest::new));
  }
}
