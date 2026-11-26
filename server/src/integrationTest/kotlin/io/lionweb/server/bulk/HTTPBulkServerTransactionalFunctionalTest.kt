package io.lionweb.server.bulk

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.lionweb.LionWebVersion
import io.lionweb.client.api.HistorySupport
import io.lionweb.client.api.RepositoryConfiguration
import io.lionweb.serialization.LowLevelJsonSerialization
import io.lionweb.serialization.data.MetaPointer
import io.lionweb.serialization.data.SerializedClassifierInstance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Checks that failing bulk commands are reported with the message kinds of the bulk API
 * specification, and leave the repository unchanged.
 */
class HTTPBulkServerTransactionalFunctionalTest : AbstractHTTPBulkServerFunctionalTest() {
    private val repo = "transactional"
    private val concept = MetaPointer.get("lang", "1", "Concept")
    private val containment = MetaPointer.get("lang", "1", "cont")
    private val http = HttpClient.newHttpClient()
    private val serialization = LowLevelJsonSerialization()

    @BeforeEach
    fun createRepository() {
        if (inMemoryServer.listRepositories().any { it.name == repo }) {
            inMemoryServer.deleteRepository(repo)
        }
        inMemoryServer.createRepository(RepositoryConfiguration(repo, LionWebVersion.v2024_1, HistorySupport.DISABLED))
        val response =
            post(
                "createPartitions",
                chunk(node("p1", null, "c1"), node("c1", "p1", "g1"), node("g1", "c1"), node("p2", null)),
            )
        assertEquals(true, response.get("success").asBoolean)
    }

    private fun node(
        id: String,
        parent: String?,
        vararg children: String,
    ): SerializedClassifierInstance {
        val n = SerializedClassifierInstance(id, concept)
        n.parentNodeID = parent
        if (children.isNotEmpty()) {
            n.unsafeAppendContainmentValue(containment, children.toList())
        }
        return n
    }

    private fun chunk(vararg nodes: SerializedClassifierInstance): String =
        serialization.serializeToJsonString(
            LowLevelJsonSerialization.groupNodesIntoSerializationBlock(nodes.toList(), LionWebVersion.v2024_1),
        )

    private fun post(
        command: String,
        body: String,
        query: String = "",
    ): JsonObject {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$serverPort/bulk/$command?repository=$repo$query"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return JsonParser.parseString(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).asJsonObject
    }

    private fun retrieveAll(): String = post("retrieve", """{"ids":["p1","p2"]}""").get("chunk").toString()

    private fun assertFailure(
        response: JsonObject,
        kind: String,
        dataKey: String? = null,
        dataValue: String? = null,
    ) {
        assertEquals(false, response.get("success").asBoolean, response.toString())
        val message = response.getAsJsonArray("messages")[0].asJsonObject
        assertEquals(kind, message.get("kind").asString, response.toString())
        if (dataKey != null) {
            assertEquals(dataValue, message.getAsJsonObject("data").get(dataKey).asString)
        }
    }

    @Test
    fun failingStoreIsReportedAndChangesNothing() {
        val before = retrieveAll()
        // c1 gets a new child (valid), g1 lists an unknown node (invalid)
        val response =
            post(
                "store",
                chunk(node("c1", "p1", "g1", "n"), node("n", "c1"), node("g1", "c1", "unknown")),
            )
        assertFailure(response, "ParentMissing", "nodeId", "unknown")
        assertEquals(before, retrieveAll())
    }

    @Test
    fun storeWithCycleOrTwoParentsIsReportedAndChangesNothing() {
        val before = retrieveAll()
        assertFailure(post("store", chunk(node("g1", "c1", "c1"), node("c1", "g1", "g1"))), "TreeCycle")
        assertFailure(
            post("store", chunk(node("p2", null, "g1"), node("c1", "p1", "g1"))),
            "NodeHasMultipleParents",
            "nodeId",
            "g1",
        )
        assertEquals(before, retrieveAll())
    }

    @Test
    fun createPartitionsOfExistingPartitionIsReportedAndChangesNothing() {
        val before = retrieveAll()
        val response = post("createPartitions", chunk(node("p3", null), node("p2", null)))
        assertFailure(response, "PartitionAlreadyExists", "nodeId", "p2")
        assertEquals(before, retrieveAll())
        assertEquals(
            listOf("p1", "p2"),
            post("listPartitions", "").getAsJsonObject("chunk").getAsJsonArray("nodes").map { it.asJsonObject.get("id").asString },
        )
    }

    @Test
    fun deletePartitionsOfNonPartitionIsReportedAndChangesNothing() {
        val before = retrieveAll()
        val response = post("deletePartitions", """["p2","c1"]""")
        assertFailure(response, "NodeIsNotPartition", "nodeId", "c1")
        assertEquals(
            "p1",
            response
                .getAsJsonArray("messages")[0]
                .asJsonObject
                .getAsJsonObject("data")
                .get("parentNodeId")
                .asString,
        )
        assertEquals(before, retrieveAll())
    }

    @Test
    fun deletePartitionsOfUnknownIdSucceeds() {
        val response = post("deletePartitions", """["unknown"]""")
        assertEquals(true, response.get("success").asBoolean)
    }

    @Test
    fun retrieveOfUnknownIdSucceedsWithIdNotFoundMessage() {
        val response = post("retrieve", """{"ids":["p2","unknown"]}""")
        assertEquals(true, response.get("success").asBoolean)
        assertEquals(1, response.getAsJsonObject("chunk").getAsJsonArray("nodes").size())
        val message = response.getAsJsonArray("messages")[0].asJsonObject
        assertEquals("IdNotFound", message.get("kind").asString)
        assertEquals("unknown", message.getAsJsonObject("data").get("nodeId").asString)
    }

    @Test
    fun retrieveWithInvalidDepthLimitIsRejected() {
        assertFailure(post("retrieve", """{"ids":["p1"]}""", "&depthLimit=-1"), "DepthLimitIncorrect", "depthLimit", "-1")
        assertFailure(post("retrieve", """{"ids":["p1"]}""", "&depthLimit=abc"), "DepthLimitIncorrect", "depthLimit", "abc")
    }

    @Test
    fun retrieveRespectsDepthLimit() {
        fun count(query: String) =
            post("retrieve", """{"ids":["p1"]}""", query)
                .getAsJsonObject("chunk")
                .getAsJsonArray("nodes")
                .size()
        assertEquals(1, count("&depthLimit=0"))
        assertEquals(2, count("&depthLimit=1"))
        assertEquals(3, count(""))
        assertEquals(3, count("&depthLimit=99999999999999999999"))
    }

    @Test
    fun retrieveWithEmptyIdListReportsIt() {
        val response = post("retrieve", """{"ids":[]}""")
        assertTrue(response.get("success").asBoolean)
        assertEquals(
            "EmptyIdList",
            response
                .getAsJsonArray("messages")[0]
                .asJsonObject
                .get("kind")
                .asString,
        )
    }
}
