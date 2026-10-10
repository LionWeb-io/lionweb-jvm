package io.lionweb.server.integration

import com.google.gson.Gson
import io.lionweb.LionWebVersion
import io.lionweb.client.delta.DeltaClient
import io.lionweb.client.delta.DeltaMessageSerialization
import io.lionweb.client.delta.messages.DeltaCommand
import io.lionweb.client.delta.messages.queries.participations.ReconnectRequest
import io.lionweb.client.delta.messages.queries.participations.ReconnectResponse
import io.lionweb.language.Concept
import io.lionweb.language.Language
import io.lionweb.model.Node
import io.lionweb.serialization.SerializationProvider
import io.lionweb.serialization.UnavailableNodePolicy
import io.lionweb.server.delta.WebSocketDeltaChannel
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * Runs inside a [TestClientAppCommand] process and accepts a single debug WebSocket connection from the
 * integration test.  Text commands are processed synchronously and a one-line response is sent
 * back on the same connection.
 *
 * Protocol:
 *   SIGN_ON                                       → OK:<participationId>
 *   SIGN_OFF                                      → OK:<state>
 *   RECONNECT <pid> <seqNum>                      → OK:<pid> | ERROR:...
 *   GET_STATE                                     → OK:<state>:<pid>
 *   SEND <deltaCommandJson>                       → OK
 *   LIST_AND_SUBSCRIBE_PARTITIONS                 → OK:<json-array-of-ids>
 *   AWAIT_PARTITION_LISTED <id> [timeoutMs]       → OK | ERROR:timeout
 *   AWAIT_PARTITION_GONE <id> [timeoutMs]         → OK | ERROR:timeout
 *   SUBSCRIBE_PARTITION_CONTENTS <id>             → OK:<nodeCount>
 *   CREATE_PARTITION <id> <classifierId>          → OK
 *   ADD_NODE <parentId> <nodeId> <classifierId>   → OK
 *   DELETE_PARTITION <id>                         → OK
 *   GET_TRACKED_NODE_IDS <partitionId>            → OK:<json-array-of-ids>
 *   AWAIT_NODE <partitionId> <nodeId> [timeoutMs] → OK | ERROR:timeout
 */
class DebugWebSocketServer(
    port: Int,
    private val deltaClient: DeltaClient,
    private val channel: WebSocketDeltaChannel,
    private val serialization: DeltaMessageSerialization,
) : WebSocketServer(InetSocketAddress(port)) {
    private val localPartitions = mutableMapOf<String, Node>()
    private val gson = Gson()
    private val lionSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1).also {
            it.setUnavailableParentPolicy(UnavailableNodePolicy.PROXY_NODES)
            it.setUnavailableReferenceTargetPolicy(UnavailableNodePolicy.PROXY_NODES)
            it.enableDynamicNodes()
        }

    override fun onOpen(
        conn: WebSocket,
        handshake: ClientHandshake,
    ) {}

    override fun onClose(
        conn: WebSocket,
        code: Int,
        reason: String,
        remote: Boolean,
    ) {}

    override fun onError(
        conn: WebSocket?,
        ex: Exception,
    ) {
        System.err.println("DebugWebSocketServer error: ${ex.message}")
    }

    override fun onStart() {}

    override fun onMessage(
        conn: WebSocket,
        message: String,
    ) {
        val response =
            try {
                dispatch(message.trim())
            } catch (e: Exception) {
                "ERROR:${e.message}"
            }
        conn.send(response)
    }

    private fun dispatch(message: String): String =
        when {
            message == "SIGN_ON" -> {
                deltaClient.sendSignOnRequest()
                "OK:${deltaClient.getParticipationId()}"
            }

            message == "SIGN_OFF" -> {
                deltaClient.sendSignOffRequest()
                "OK:${deltaClient.getState()}"
            }

            message.startsWith("RECONNECT ") -> {
                val parts = message.removePrefix("RECONNECT ").trim().split(" ", limit = 2)
                if (parts.size != 2) return "ERROR:usage: RECONNECT <participationId> <lastSeqNum>"
                val pid = parts[0]
                val lastSeqNum =
                    parts[1].toLongOrNull()
                        ?: return "ERROR:lastSeqNum must be a number"
                // Call channel.sendQuery directly so we can inspect the typed response.
                // DeltaClient.sendReconnectRequest discards the return value, making
                // success/failure indistinguishable when starting from a CONNECTED state.
                val response = channel.sendQuery { queryId -> ReconnectRequest(queryId, pid, lastSeqNum) }
                if (response is ReconnectResponse) "OK:$pid" else "ERROR:reconnect rejected"
            }

            message == "GET_STATE" -> {
                "OK:${deltaClient.getState()}:${deltaClient.getParticipationId()}"
            }

            message.startsWith("SEND ") -> {
                val json = message.removePrefix("SEND ").trim()
                val command =
                    serialization.deserialize(json) as? DeltaCommand
                        ?: return "ERROR:could not deserialize as DeltaCommand"
                val participationId =
                    deltaClient.getParticipationId()
                        ?: return "ERROR:client is not signed on"
                channel.sendCommand(participationId) { _ -> command }
                "OK"
            }

            message == "LIST_AND_SUBSCRIBE_PARTITIONS" -> {
                val resp = deltaClient.sendListAndSubscribePartitionsRequest()
                val ids = resp.partitions.classifierInstances.map { it.id }
                "OK:${gson.toJson(ids)}"
            }

            message.startsWith("AWAIT_PARTITION_LISTED ") -> {
                val parts = message.removePrefix("AWAIT_PARTITION_LISTED ").trim().split(" ", limit = 2)
                val partitionId = parts[0]
                val timeoutMs = if (parts.size == 2) parts[1].toLongOrNull() ?: 3000L else 3000L
                awaitPartitionPredicate(partitionId, timeoutMs, present = true)
            }

            message.startsWith("AWAIT_PARTITION_GONE ") -> {
                val parts = message.removePrefix("AWAIT_PARTITION_GONE ").trim().split(" ", limit = 2)
                val partitionId = parts[0]
                val timeoutMs = if (parts.size == 2) parts[1].toLongOrNull() ?: 3000L else 3000L
                awaitPartitionPredicate(partitionId, timeoutMs, present = false)
            }

            message.startsWith("SUBSCRIBE_PARTITION_CONTENTS ") -> {
                val partitionId = message.removePrefix("SUBSCRIBE_PARTITION_CONTENTS ").trim()
                val resp = deltaClient.sendSubscribeToPartitionContentsRequest(partitionId)
                // The delta wire format omits serializationFormatVersion; set it so the standard
                // deserialization can validate and parse the chunk correctly.
                resp.contents.setSerializationFormatVersion(LionWebVersion.v2024_1.versionString)
                val nodes = lionSerialization.deserializeSerializationChunk(resp.contents)
                val root =
                    nodes.filterIsInstance<Node>().firstOrNull { it.id == partitionId }
                        ?: return "ERROR:partition root $partitionId not found in subscription response"
                localPartitions[partitionId] = root
                deltaClient.monitorPartition(root)
                "OK:${nodes.size}"
            }

            message.startsWith("CREATE_PARTITION ") -> {
                val parts = message.removePrefix("CREATE_PARTITION ").trim().split(" ", limit = 2)
                if (parts.size != 2) return "ERROR:usage: CREATE_PARTITION <id> <classifierId>"
                val (partitionId, classifierId) = parts
                val language = Language(classifierId, partitionId, classifierId)
                deltaClient.sendAddPartitionCommand(language)
                deltaClient.monitorPartition(language)
                localPartitions[partitionId] = language
                "OK"
            }

            message.startsWith("ADD_NODE ") -> {
                val parts = message.removePrefix("ADD_NODE ").trim().split(" ", limit = 3)
                if (parts.size != 3) return "ERROR:usage: ADD_NODE <parentId> <nodeId> <classifierId>"
                val (parentId, nodeId, classifierId) = parts
                val language =
                    localPartitions[parentId] as? Language
                        ?: return "ERROR:parent $parentId not tracked or is not a Language"
                // Concept constructor auto-adds itself to language.addElement(), triggering the
                // MonitoringObserver which sends an AddChild command to the server.
                Concept(language, classifierId, nodeId, classifierId)
                "OK"
            }

            message.startsWith("DELETE_PARTITION ") -> {
                val partitionId = message.removePrefix("DELETE_PARTITION ").trim()
                deltaClient.sendDeletePartitionCommand(partitionId)
                localPartitions.remove(partitionId)
                "OK"
            }

            message.startsWith("GET_TRACKED_NODE_IDS ") -> {
                val partitionId = message.removePrefix("GET_TRACKED_NODE_IDS ").trim()
                val root =
                    localPartitions[partitionId]
                        ?: return "ERROR:partition $partitionId is not tracked"
                val ids = root.thisAndAllDescendants().map { it.id }
                "OK:${gson.toJson(ids)}"
            }

            message.startsWith("AWAIT_NODE ") -> {
                val parts = message.removePrefix("AWAIT_NODE ").trim().split(" ", limit = 3)
                if (parts.size < 2) return "ERROR:usage: AWAIT_NODE <partitionId> <nodeId> [timeoutMs]"
                val partitionId = parts[0]
                val nodeId = parts[1]
                val timeoutMs = if (parts.size == 3) parts[2].toLongOrNull() ?: 3000L else 3000L
                val deadline = System.currentTimeMillis() + timeoutMs
                var found = false
                while (System.currentTimeMillis() < deadline && !found) {
                    val root = localPartitions[partitionId]
                    if (root != null && root.thisAndAllDescendants().any { it.id == nodeId }) {
                        found = true
                    } else {
                        Thread.sleep(50)
                    }
                }
                if (found) "OK" else "ERROR:timeout waiting for node $nodeId in partition $partitionId"
            }

            else -> "ERROR:unknown debug command: $message"
        }

    /**
     * Polls [DeltaClient.sendListPartitionsRequest] until [partitionId] appears in (or disappears
     * from) the server's partition list, or [timeoutMs] elapses.  Because the query travels over the
     * same WebSocket connection as any pending commands from this client, each successful response
     * implies those commands have been committed.
     */
    private fun awaitPartitionPredicate(
        partitionId: String,
        timeoutMs: Long,
        present: Boolean,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ids =
                deltaClient
                    .sendListPartitionsRequest()
                    .partitions.classifierInstances
                    .map { it.id }
            val condition = if (present) ids.contains(partitionId) else !ids.contains(partitionId)
            if (condition) return "OK"
            Thread.sleep(50)
        }
        val verb = if (present) "appear in" else "disappear from"
        return "ERROR:timeout waiting for partition $partitionId to $verb server list"
    }
}
