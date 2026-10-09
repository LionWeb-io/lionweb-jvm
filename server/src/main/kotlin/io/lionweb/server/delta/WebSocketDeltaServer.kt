package io.lionweb.server.delta

import com.google.gson.JsonParser
import io.lionweb.client.delta.DeltaChannel
import io.lionweb.client.delta.DeltaCommandReceiver
import io.lionweb.client.delta.DeltaEventReceiver
import io.lionweb.client.delta.DeltaMessageSerialization
import io.lionweb.client.delta.DeltaQueryReceiver
import io.lionweb.client.delta.DeltaQueryResponseReceiver
import io.lionweb.client.delta.messages.DeltaCommand
import io.lionweb.client.delta.messages.DeltaEvent
import io.lionweb.client.delta.messages.DeltaQuery
import io.lionweb.client.delta.messages.DeltaQueryResponse
import io.lionweb.client.delta.messages.events.ClassifierChanged
import io.lionweb.client.delta.messages.events.annotations.AnnotationAdded
import io.lionweb.client.delta.messages.events.annotations.AnnotationDeleted
import io.lionweb.client.delta.messages.events.annotations.AnnotationMovedFromOtherParent
import io.lionweb.client.delta.messages.events.annotations.AnnotationMovedInSameParent
import io.lionweb.client.delta.messages.events.annotations.AnnotationReplaced
import io.lionweb.client.delta.messages.events.children.ChildAdded
import io.lionweb.client.delta.messages.events.children.ChildDeleted
import io.lionweb.client.delta.messages.events.children.ChildMovedFromOtherContainment
import io.lionweb.client.delta.messages.events.children.ChildMovedFromOtherContainmentInSameParent
import io.lionweb.client.delta.messages.events.children.ChildMovedInSameContainment
import io.lionweb.client.delta.messages.events.children.ChildReplaced
import io.lionweb.client.delta.messages.events.partitions.PartitionAdded
import io.lionweb.client.delta.messages.events.partitions.PartitionDeleted
import io.lionweb.client.delta.messages.events.properties.PropertyAdded
import io.lionweb.client.delta.messages.events.properties.PropertyChanged
import io.lionweb.client.delta.messages.events.properties.PropertyDeleted
import io.lionweb.client.delta.messages.events.references.ReferenceAdded
import io.lionweb.client.delta.messages.events.references.ReferenceChanged
import io.lionweb.client.delta.messages.events.references.ReferenceDeleted
import io.lionweb.client.delta.messages.queries.ListAndSubscribePartitionsRequest
import io.lionweb.client.delta.messages.queries.participations.ReconnectRequest
import io.lionweb.client.delta.messages.queries.participations.SignOffRequest
import io.lionweb.client.delta.messages.queries.participations.SignOnRequest
import io.lionweb.client.delta.messages.queries.participations.SignOnResponse
import io.lionweb.client.delta.messages.queries.subscriptions.SubscribeToPartitionContentsRequest
import io.lionweb.client.delta.messages.queries.subscriptions.UnsubscribeFromPartitionContentsRequest
import io.lionweb.client.inmemory.InMemoryServer
import io.lionweb.server.ui.MessageLog
import io.lionweb.server.ui.MessageLogEntry
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function

/**
 * Accepts WebSocket connections from delta-protocol clients and routes messages through a shared
 * broadcast channel wired to an [InMemoryServer].
 *
 * Wire format for commands includes an extra `participationId` field injected alongside the
 * standard `messageKind` fields, because [DeltaCommand] does not carry that field internally.
 */
class WebSocketDeltaServer(
    port: Int,
    private val inMemoryServer: InMemoryServer,
    private val repositoryName: String,
    val messageLog: MessageLog? = null,
) : WebSocketServer(InetSocketAddress(port)) {
    private val serialization = DeltaMessageSerialization()
    val broadcastChannel = BroadcastChannel()
    private val startLatch = CountDownLatch(1)
    private val startError = AtomicReference<Exception?>(null)
    private val connectionClientIds = ConcurrentHashMap<WebSocket, String>()
    private val connectionParticipations = ConcurrentHashMap<WebSocket, String>()

    // Tracks which connections have subscribed to partition lifecycle events (PartitionAdded/Deleted).
    private val lifecycleSubscribers = CopyOnWriteArrayList<WebSocket>()

    // Tracks which partition IDs each connection has subscribed to for content events.
    private val contentSubscriptions = ConcurrentHashMap<WebSocket, MutableSet<String>>()

    init {
        inMemoryServer.monitorDeltaChannel(repositoryName, broadcastChannel)
    }

    override fun onOpen(
        conn: WebSocket,
        handshake: ClientHandshake,
    ) {
        broadcastChannel.addConnection(conn)
    }

    override fun onClose(
        conn: WebSocket,
        code: Int,
        reason: String,
        remote: Boolean,
    ) {
        broadcastChannel.removeConnection(conn)
        connectionClientIds.remove(conn)
        connectionParticipations.remove(conn)
        lifecycleSubscribers.remove(conn)
        contentSubscriptions.remove(conn)
    }

    override fun onMessage(
        conn: WebSocket,
        message: String,
    ) {
        val root = JsonParser.parseString(message).asJsonObject
        val kind = root.get("messageKind")?.asString ?: return
        val targetClass = serialization.getClassForKind(kind) ?: return

        when {
            DeltaMessageSerialization.isCommandClass(targetClass) -> {
                // Commands carry participationId as an extra wire field injected by
                // WebSocketDeltaChannel.sendCommand – strip it before deserializing.
                val participationId = root.get("participationId")?.asString ?: return
                messageLog?.add(
                    MessageLogEntry(
                        timestamp = System.currentTimeMillis(),
                        direction = "received",
                        category = "command",
                        messageKind = kind,
                        clientId = connectionClientIds[conn],
                        participationId = participationId,
                        json = message,
                    ),
                )
                root.remove("participationId")
                val command = serialization.deserialize(root.toString()) as? DeltaCommand ?: return
                broadcastChannel.commandReceiver?.receiveCommand(participationId, command)
            }
            DeltaMessageSerialization.isQueryClass(targetClass) -> {
                val query = serialization.deserialize(message) as? DeltaQuery ?: return
                // Update connection maps from typed message objects — no JSON parsing needed.
                when (query) {
                    is SignOnRequest -> connectionClientIds[conn] = query.clientId
                    is ReconnectRequest -> {
                        query.clientId?.let { connectionClientIds[conn] = it }
                        connectionParticipations[conn] = query.participationId
                    }
                    is SignOffRequest -> connectionParticipations.remove(conn)
                    is ListAndSubscribePartitionsRequest -> lifecycleSubscribers.add(conn)
                    is SubscribeToPartitionContentsRequest ->
                        contentSubscriptions.getOrPut(conn) { ConcurrentHashMap.newKeySet() }.add(query.partition)
                    is UnsubscribeFromPartitionContentsRequest ->
                        contentSubscriptions[conn]?.remove(query.partition)
                }
                messageLog?.add(
                    MessageLogEntry(
                        timestamp = System.currentTimeMillis(),
                        direction = "received",
                        category = "query",
                        messageKind = kind,
                        clientId = connectionClientIds[conn],
                        participationId = connectionParticipations[conn],
                        json = message,
                    ),
                )
                val response = broadcastChannel.queryReceiver?.receiveQuery(query)
                if (response != null) {
                    if (response is SignOnResponse) {
                        connectionParticipations[conn] = response.participationId
                    }
                    val responseJson = serialization.serialize(response)
                    messageLog?.add(
                        MessageLogEntry(
                            timestamp = System.currentTimeMillis(),
                            direction = "sent",
                            category = "response",
                            messageKind = response.javaClass.simpleName,
                            clientId = connectionClientIds[conn],
                            participationId = connectionParticipations[conn],
                            json = responseJson,
                        ),
                    )
                    conn.send(responseJson)
                }
            }
        }
    }

    override fun onError(
        conn: WebSocket?,
        ex: Exception,
    ) {
        if (conn == null) {
            // Server-level error (e.g. port already in use) — signal startup failure.
            startError.set(ex)
            startLatch.countDown()
        } else {
            System.err.println("WebSocket error on ${conn.remoteSocketAddress}: ${ex.message}")
        }
    }

    override fun onStart() {
        startLatch.countDown()
    }

    /**
     * Waits until the server has either bound successfully or failed, then throws if it failed.
     * Call this immediately after [start] to get a reliable "is it actually listening?" answer.
     */
    fun awaitStart(timeoutSeconds: Long = 5) {
        if (!startLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw IllegalStateException("WebSocket server did not start within ${timeoutSeconds}s")
        }
        startError.get()?.let { throw IllegalStateException("WebSocket server failed to start: ${it.message}", it) }
    }

    /**
     * Walks the in-memory node tree upward from [nodeId] to find the partition root ID.
     * Returns null if the node is not found.
     */
    private fun findPartitionOf(nodeId: String): String? {
        val partitionIds =
            try {
                inMemoryServer.listPartitionIDs(repositoryName).toHashSet()
            } catch (_: Exception) {
                return null
            }
        var current = nodeId
        while (true) {
            if (current in partitionIds) return current
            val nodes =
                try {
                    inMemoryServer.retrieve(repositoryName, listOf(current), 0)
                } catch (_: Exception) {
                    return null
                }
            val parent = nodes.firstOrNull()?.parentNodeID ?: return null
            current = parent
        }
    }

    /**
     * Returns the set of partition IDs relevant to [event] for subscription filtering.
     * An empty set means no content-based filtering applies (send to all or use lifecycle logic).
     */
    private fun relevantPartitionsForEvent(event: DeltaEvent): Set<String> =
        when (event) {
            is PropertyChanged -> setOfNotNull(findPartitionOf(event.node))
            is PropertyAdded -> setOfNotNull(findPartitionOf(event.node))
            is PropertyDeleted -> setOfNotNull(findPartitionOf(event.node))
            is ClassifierChanged -> setOfNotNull(findPartitionOf(event.node))
            is ChildAdded -> setOfNotNull(findPartitionOf(event.parent))
            is ChildDeleted -> setOfNotNull(findPartitionOf(event.parent))
            is ChildMovedInSameContainment -> setOfNotNull(findPartitionOf(event.parent))
            is ChildMovedFromOtherContainmentInSameParent -> setOfNotNull(findPartitionOf(event.parent ?: throw IllegalStateException()))
            is ChildMovedFromOtherContainment ->
                setOfNotNull(findPartitionOf(event.oldParent ?: throw IllegalStateException()), findPartitionOf(event.newParent))
            is ChildReplaced -> setOfNotNull(findPartitionOf(event.parent))
            is ReferenceAdded -> setOfNotNull(findPartitionOf(event.parent))
            is ReferenceChanged -> setOfNotNull(findPartitionOf(event.parent))
            is ReferenceDeleted -> setOfNotNull(findPartitionOf(event.parent))
            is AnnotationAdded -> setOfNotNull(findPartitionOf(event.parent))
            is AnnotationDeleted -> setOfNotNull(findPartitionOf(event.parent))
            is AnnotationMovedInSameParent -> setOfNotNull(findPartitionOf(event.parent))
            is AnnotationReplaced -> setOfNotNull(findPartitionOf(event.parent))
            is AnnotationMovedFromOtherParent ->
                setOfNotNull(findPartitionOf(event.oldParent), findPartitionOf(event.newParent))
            else -> emptySet()
        }

    /**
     * A [DeltaChannel] that routes events only to connections that have subscribed to the
     * relevant partition. Partition lifecycle events (PartitionAdded/Deleted) are delivered only
     * to connections that called ListAndSubscribePartitions. Content events are delivered only to
     * connections subscribed to the affected partition(s). Unknown event types fall back to
     * broadcasting to all connections.
     */
    inner class BroadcastChannel : DeltaChannel {
        private val connections = CopyOnWriteArrayList<WebSocket>()
        private val eventReceivers = CopyOnWriteArrayList<DeltaEventReceiver>()
        private val queryResponseReceivers = CopyOnWriteArrayList<DeltaQueryResponseReceiver>()
        private val nextEventId = AtomicInteger(1)

        var commandReceiver: DeltaCommandReceiver? = null
        var queryReceiver: DeltaQueryReceiver? = null

        fun addConnection(conn: WebSocket) = connections.add(conn)

        fun removeConnection(conn: WebSocket) = connections.remove(conn)

        override fun sendEvent(eventProducer: Function<Int, DeltaEvent>) {
            val event = eventProducer.apply(nextEventId.getAndIncrement())
            val json = serialization.serialize(event)
            messageLog?.add(
                MessageLogEntry(
                    timestamp = System.currentTimeMillis(),
                    direction = "sent",
                    category = "event",
                    messageKind = event.javaClass.simpleName,
                    json = json,
                ),
            )
            val targets: List<WebSocket> =
                when (event) {
                    is PartitionAdded, is PartitionDeleted ->
                        lifecycleSubscribers.filter { it.isOpen }
                    else -> {
                        val partitions = relevantPartitionsForEvent(event)
                        if (partitions.isEmpty()) {
                            connections.filter { it.isOpen }
                        } else {
                            connections.filter { conn ->
                                conn.isOpen && contentSubscriptions[conn]?.any { it in partitions } == true
                            }
                        }
                    }
                }
            targets.forEach { it.send(json) }
            eventReceivers.forEach { it.receiveEvent(event) }
        }

        override fun sendQuery(queryProducer: Function<String, DeltaQuery>): DeltaQueryResponse? = null

        override fun sendCommand(
            participationId: String,
            commandProducer: Function<String, DeltaCommand>,
        ) {}

        override fun registerEventReceiver(r: DeltaEventReceiver) {
            eventReceivers.add(r)
        }

        override fun unregisterEventReceiver(r: DeltaEventReceiver) {
            eventReceivers.remove(r)
        }

        override fun registerCommandReceiver(r: DeltaCommandReceiver) {
            commandReceiver = r
        }

        override fun unregisterCommandReceiver(r: DeltaCommandReceiver) {
            if (commandReceiver === r) commandReceiver = null
        }

        override fun registerQueryReceiver(r: DeltaQueryReceiver) {
            queryReceiver = r
        }

        override fun unregisterQueryReceiver(r: DeltaQueryReceiver) {
            if (queryReceiver === r) queryReceiver = null
        }

        override fun registerQueryResponseReceiver(r: DeltaQueryResponseReceiver) {
            queryResponseReceivers.add(r)
        }

        override fun unregisterQueryResponseReceiver(r: DeltaQueryResponseReceiver) {
            queryResponseReceivers.remove(r)
        }
    }
}
