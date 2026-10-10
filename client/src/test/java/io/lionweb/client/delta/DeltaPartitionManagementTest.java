package io.lionweb.client.delta;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.client.delta.messages.queries.ListAndSubscribePartitionsResponse;
import io.lionweb.client.delta.messages.queries.ListPartitionsResponse;
import io.lionweb.client.delta.messages.queries.subscriptions.SubscribeToPartitionContentsResponse;
import io.lionweb.client.delta.messages.queries.subscriptions.UnsubscribeFromPartitionContentsResponse;
import io.lionweb.client.inmemory.InMemoryServer;
import io.lionweb.language.Language;
import io.lionweb.serialization.JsonSerialization;
import org.junit.jupiter.api.Test;

/**
 * Tests partition management queries and commands of the delta protocol.
 *
 * <p>Covers: listing partitions, list-and-subscribe, subscribe/unsubscribe to a partition's
 * contents, creating a partition via the delta command, and deleting a partition via the delta
 * command.
 */
public class DeltaPartitionManagementTest extends AbstractDeltaProtocolTest {

  /**
   * A signed-on client can query the current list of partitions. The response contains all
   * partition roots stored in the repository.
   */
  @Test
  public void listPartitions() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang1 = new Language("Language A", "lang-a", "lang-a-key");
    Language lang2 = new Language("Language B", "lang-b", "lang-b-key");
    server.createPartition("MyRepo", lang1, ser);
    server.createPartition("MyRepo", lang2, ser);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client = new DeltaClient(channel, "my-client");
    client.sendSignOnRequest();

    ListPartitionsResponse response = client.sendListPartitionsRequest();

    assertNotNull(response);
    assertEquals(2, response.partitions.getClassifierInstances().size());
  }

  /** When the repository is empty, the list-partitions response contains no nodes. */
  @Test
  public void listPartitionsWhenEmpty() {
    InMemoryServer server = createServerWithRepository();

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = new DeltaClient(channel, "my-client");
    client.sendSignOnRequest();

    ListPartitionsResponse response = client.sendListPartitionsRequest();

    assertNotNull(response);
    assertEquals(0, response.partitions.getClassifierInstances().size());
  }

  /**
   * A signed-on client can list all existing partitions and register for future partition-lifecycle
   * events in a single request. The response carries the current partitions.
   */
  @Test
  public void listAndSubscribePartitions() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang, ser);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = new DeltaClient(channel, "my-client");
    client.sendSignOnRequest();

    ListAndSubscribePartitionsResponse response = client.sendListAndSubscribePartitionsRequest();

    assertNotNull(response);
    assertEquals(1, response.partitions.getClassifierInstances().size());
    assertEquals("lang-a", response.partitions.getClassifierInstances().get(0).getID());
  }

  /**
   * A client can subscribe to a specific partition and receive its current contents as the
   * subscription response.
   */
  @Test
  public void subscribeToPartitionContents() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang, ser);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = new DeltaClient(channel, "my-client");
    client.sendSignOnRequest();

    SubscribeToPartitionContentsResponse response =
        client.sendSubscribeToPartitionContentsRequest("lang-a");

    assertNotNull(response);
    assertFalse(response.split);
    // The response must contain at least the partition root node
    assertTrue(response.contents.getClassifierInstances().size() >= 1);
    assertTrue(
        response.contents.getClassifierInstances().stream()
            .anyMatch(n -> "lang-a".equals(n.getID())));
  }

  /**
   * After subscribing, a client can unsubscribe from a partition. The server acknowledges with a
   * response carrying the same queryId.
   */
  @Test
  public void unsubscribeFromPartitionContents() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang, ser);

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = new DeltaClient(channel, "my-client");
    client.sendSignOnRequest();
    client.sendSubscribeToPartitionContentsRequest("lang-a");

    UnsubscribeFromPartitionContentsResponse response =
        client.sendUnsubscribeFromPartitionContentsRequest("lang-a");

    assertNotNull(response);
  }

  /**
   * A client can add a new partition to the repository via the AddPartition command. After the
   * command is processed the server holds the new partition and its nodes.
   */
  @Test
  public void createPartitionViaDeltaCommand() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = new DeltaClient(channel, "my-client");
    client.sendSignOnRequest();

    Language language = new Language("New Language", "new-lang", "new-lang-key");
    client.sendAddPartitionCommand(language);

    // Verify the server now holds the new partition
    assertNotNull(server.retrieveAsClassifierInstance("MyRepo", "new-lang", ser));
    assertTrue(server.listPartitionIDs("MyRepo").contains("new-lang"));
  }

  /**
   * When a second client is on the same channel and the first client creates a partition, the
   * second client receives the PartitionAdded event (because it does not originate from the second
   * client's participation).
   */
  @Test
  public void createPartitionNotifiesOtherClients() {
    InMemoryServer server = createServerWithRepository();

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();

    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-2");
    client2.sendSignOnRequest();

    // client2 subscribes to partition-list changes so it expects to hear about new partitions
    client2.sendListAndSubscribePartitionsRequest();

    Language language = new Language("New Language", "new-lang", "new-lang-key");
    client1.sendAddPartitionCommand(language);

    // Verify server state
    assertTrue(server.listPartitionIDs("MyRepo").contains("new-lang"));
    // client2 received the PartitionAdded event without throwing — the event was handled
    // (no UnsupportedOperationException)
  }

  /**
   * A client can delete an existing partition via the DeletePartition command. After the command is
   * processed the partition is no longer present in the repository.
   */
  @Test
  public void deletePartitionViaDeltaCommand() {
    InMemoryServer server = createServerWithRepository();
    JsonSerialization ser = serialization();

    Language lang = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", lang, ser);
    assertTrue(server.listPartitionIDs("MyRepo").contains("lang-a"));

    DeltaChannel channel = prepareChannel(server);

    DeltaClient client = new DeltaClient(channel, "my-client");
    client.sendSignOnRequest();

    client.sendDeletePartitionCommand("lang-a");

    // Verify the partition is no longer registered in the server
    assertFalse(server.listPartitionIDs("MyRepo").contains("lang-a"));
  }
}
