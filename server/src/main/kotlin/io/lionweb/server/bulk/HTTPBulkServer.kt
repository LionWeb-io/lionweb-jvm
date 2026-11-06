package io.lionweb.server.bulk

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.lionweb.LionWebVersion
import io.lionweb.client.api.ClassifierKey
import io.lionweb.client.api.ClassifierResult
import io.lionweb.client.api.HistorySupport
import io.lionweb.client.api.RepositoryConfiguration
import io.lionweb.client.api.RepositoryVersionToken
import io.lionweb.client.inmemory.BulkCommandException
import io.lionweb.client.inmemory.BulkMessageKind
import io.lionweb.client.inmemory.InMemoryServer
import io.lionweb.serialization.LowLevelJsonSerialization
import io.lionweb.serialization.data.SerializationChunk
import io.lionweb.serialization.data.SerializedClassifierInstance
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.zip.GZIPInputStream

class HTTPBulkServer(
    val inMemoryServer: InMemoryServer,
    val port: Int,
) {
    private val gson = GsonBuilder().serializeNulls().create()
    private val lowLevelJsonSerialization = LowLevelJsonSerialization()
    private var server: HttpServer? = null

    fun start(): HttpServer {
        val newServer = HttpServer.create(InetSocketAddress(port), 0)
        newServer.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (e: Throwable) {
                exchange.sendJson(HttpURLConnection.HTTP_INTERNAL_ERROR, failure(e.message ?: e.javaClass.name))
            }
        }
        newServer.executor = null
        newServer.start()
        server = newServer
        return newServer
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        if (exchange.requestMethod == "OPTIONS") {
            exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Content-Encoding, Authorization")
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_NO_CONTENT, -1)
            return
        }

        when (exchange.requestURI.path) {
            "/bulk/ids" -> exchange.requireMethod("POST") { handleIds(exchange) }
            "/bulk/listPartitions" -> exchange.requireMethod("POST") { handleListPartitions(exchange) }
            "/bulk/createPartitions" -> exchange.requireMethod("POST") { handleCreatePartitions(exchange) }
            "/bulk/deletePartitions" -> exchange.requireMethod("POST") { handleDeletePartitions(exchange) }
            "/bulk/store" -> exchange.requireMethod("POST") { handleStore(exchange) }
            "/bulk/retrieve" -> exchange.requireMethod("POST") { handleRetrieve(exchange) }
            "/inspection/nodesByClassifier" -> exchange.requireMethod("GET") { handleNodesByClassifier(exchange) }
            "/inspection/nodesByLanguage" -> exchange.requireMethod("GET") { handleNodesByLanguage(exchange) }
            "/createRepository" -> exchange.requireMethod("POST") { handleCreateRepository(exchange) }
            "/deleteRepository" -> exchange.requireMethod("POST") { handleDeleteRepository(exchange) }
            "/createDatabase" -> exchange.requireMethod("POST") { exchange.sendJson(HttpURLConnection.HTTP_OK, success()) }
            "/listRepositories" -> exchange.requireMethod("POST") { handleListRepositories(exchange) }
            else -> exchange.sendJson(HttpURLConnection.HTTP_NOT_FOUND, failure("Unknown endpoint: ${exchange.requestURI.path}"))
        }
    }

    private fun handleIds(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        val count = exchange.queryParam("count")?.toIntOrNull() ?: 0
        val ids =
            synchronized(inMemoryServer) {
                inMemoryServer.ids(repository, count)
            }
        val response = success()
        response.add("ids", JsonArray().also { ids.forEach(it::add) })
        exchange.sendJson(HttpURLConnection.HTTP_OK, response)
    }

    private fun handleListPartitions(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        val nodes =
            synchronized(inMemoryServer) {
                val partitionIds = inMemoryServer.listPartitionIDs(repository)
                if (partitionIds.isEmpty()) {
                    emptyList()
                } else {
                    inMemoryServer.retrieve(repository, partitionIds, 0)
                }
            }
        exchange.sendJson(HttpURLConnection.HTTP_OK, chunkResponse(repository, nodes))
    }

    private fun handleCreatePartitions(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        val chunk = exchange.readSerializationChunk()
        val version =
            synchronized(inMemoryServer) {
                inMemoryServer.createPartitionFromChunk(repository, chunk.classifierInstances)
            }
        exchange.sendJson(HttpURLConnection.HTTP_OK, success(version))
    }

    private fun handleDeletePartitions(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        val ids =
            JsonParser.parseString(exchange.readRequestBodyText()).asJsonArray.map {
                it.asString
            }
        val version =
            synchronized(inMemoryServer) {
                inMemoryServer.deletePartitions(repository, ids)
            }
        exchange.sendJson(HttpURLConnection.HTTP_OK, success(version))
    }

    private fun handleStore(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        val chunk = exchange.readSerializationChunk()
        val version =
            synchronized(inMemoryServer) {
                inMemoryServer.store(repository, chunk.classifierInstances)
            }
        exchange.sendJson(HttpURLConnection.HTTP_OK, success(version))
    }

    private fun handleRetrieve(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        val limit = exchange.depthLimit()
        val idsObject = JsonParser.parseString(exchange.readRequestBodyText()).asJsonObject
        val ids =
            try {
                idsObject.getAsJsonArray("ids")?.map {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                    it.asString
                } ?: emptyList()
            } catch (e: RuntimeException) {
                throw BulkCommandException(
                    BulkMessageKind.IDS_INCORRECT,
                    "The ids parameter must be a list of strings",
                    mapOf("nodeIds" to (idsObject.get("ids")?.toString() ?: "null")),
                )
            }
        if (ids.isEmpty()) {
            // Validate the repository name and the depth limit even if there is nothing to retrieve
            inMemoryServer.getRepositoryConfiguration(repository)
            val response = chunkResponse(repository, emptyList())
            response.getAsJsonArray("messages").add(message(BulkMessageKind.EMPTY_ID_LIST.kindName, "Empty id list", emptyMap()))
            exchange.sendJson(HttpURLConnection.HTTP_OK, response)
            return
        }
        val result =
            synchronized(inMemoryServer) {
                inMemoryServer.retrieveReportingUnknown(repository, ids, limit)
            }
        val response = chunkResponse(repository, result.nodes)
        result.unknownIds.forEach {
            response.getAsJsonArray("messages").add(
                message(
                    BulkMessageKind.ID_NOT_FOUND.kindName,
                    "Node with id $it does not exist",
                    mapOf(
                        "nodeId" to it,
                    ),
                ),
            )
        }
        exchange.sendJson(HttpURLConnection.HTTP_OK, response)
    }

    private fun handleNodesByClassifier(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        val limit = exchange.queryParam("limit")?.toIntOrNull()
        val results =
            synchronized(inMemoryServer) {
                inMemoryServer.nodesByClassifier(repository, limit)
            }
        val response = JsonArray()
        results.entries.forEach { (key, result) ->
            response.add(classifierResultJson(key, result))
        }
        exchange.sendJson(HttpURLConnection.HTTP_OK, response)
    }

    private fun handleNodesByLanguage(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        val limit = exchange.queryParam("limit")?.toIntOrNull()
        val results =
            synchronized(inMemoryServer) {
                inMemoryServer.nodesByLanguage(repository, limit)
            }
        val response = JsonArray()
        results.entries.forEach { (language, result) ->
            response.add(
                classifierResultJson(result).also {
                    it.addProperty("language", language)
                },
            )
        }
        exchange.sendJson(HttpURLConnection.HTTP_OK, response)
    }

    private fun handleCreateRepository(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        val lionWebVersion =
            exchange.queryParam("lionWebVersion")?.let(LionWebVersion::fromValue)
                ?: LionWebVersion.currentVersion
        val history = HistorySupport.fromBoolean(exchange.queryParam("history")?.toBooleanStrictOrNull() ?: false)
        synchronized(inMemoryServer) {
            inMemoryServer.createRepository(RepositoryConfiguration(repository, lionWebVersion, history))
        }
        exchange.sendJson(HttpURLConnection.HTTP_OK, success())
    }

    private fun handleDeleteRepository(exchange: HttpExchange) {
        val repository = exchange.requiredQueryParam("repository")
        synchronized(inMemoryServer) {
            inMemoryServer.deleteRepository(repository)
        }
        exchange.sendJson(HttpURLConnection.HTTP_OK, success())
    }

    private fun handleListRepositories(exchange: HttpExchange) {
        val repositories =
            synchronized(inMemoryServer) {
                inMemoryServer.listRepositories()
            }
        val response = success()
        val repositoriesJson = JsonArray()
        repositories.forEach { repository ->
            repositoriesJson.add(
                JsonObject().also {
                    it.addProperty("name", repository.name)
                    it.addProperty("lionweb_version", repository.lionWebVersion.versionString)
                    it.addProperty("history", repository.historySupport.toBoolean())
                },
            )
        }
        response.add("repositories", repositoriesJson)
        exchange.sendJson(HttpURLConnection.HTTP_OK, response)
    }

    private fun HttpExchange.requireMethod(
        method: String,
        handler: () -> Unit,
    ) {
        if (requestMethod != method) {
            sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1)
            return
        }
        try {
            handler()
        } catch (e: BulkCommandException) {
            sendJson(HttpURLConnection.HTTP_BAD_REQUEST, failure(e.kind.kindName, e.message ?: e.kind.kindName, e.data))
        } catch (e: IllegalArgumentException) {
            sendJson(HttpURLConnection.HTTP_BAD_REQUEST, failure(e.message ?: e.javaClass.name))
        }
    }

    /**
     * The depth limit is optional (no limit when omitted), and otherwise it must be an integer >= 0.
     * Values too big for an Int are as good as no limit.
     */
    private fun HttpExchange.depthLimit(): Int {
        val value = queryParam("depthLimit") ?: return Int.MAX_VALUE
        val number = value.toBigIntegerOrNull()
        if (number == null || number.signum() < 0) {
            throw BulkCommandException(
                BulkMessageKind.DEPTH_LIMIT_INCORRECT,
                "The depth limit must be an integer >= 0, but it is '$value'",
                mapOf("depthLimit" to value),
            )
        }
        return if (number.bitLength() > 31) Int.MAX_VALUE else number.toInt()
    }

    private fun HttpExchange.readSerializationChunk(): SerializationChunk =
        lowLevelJsonSerialization.deserializeSerializationBlock(readRequestBodyText())

    private fun HttpExchange.readRequestBodyText(): String {
        val stream =
            if (requestHeaders.getFirst("Content-Encoding")?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(requestBody)
            } else {
                requestBody
            }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun chunkResponse(
        repository: String,
        nodes: List<SerializedClassifierInstance>,
    ): JsonObject {
        val lionWebVersion = inMemoryServer.getRepositoryConfiguration(repository).lionWebVersion
        val chunk = LowLevelJsonSerialization.groupNodesIntoSerializationBlock(nodes, lionWebVersion)
        val response = success()
        response.add("chunk", lowLevelJsonSerialization.serializeToJsonElement(chunk))
        return response
    }

    private fun classifierResultJson(
        key: ClassifierKey,
        result: ClassifierResult,
    ): JsonObject =
        classifierResultJson(result).also {
            it.addProperty("language", key.languageKey)
            it.addProperty("classifier", key.classifierKey)
        }

    private fun classifierResultJson(result: ClassifierResult): JsonObject =
        JsonObject().also {
            it.add("ids", JsonArray().also { ids -> result.ids.forEach(ids::add) })
            it.addProperty("size", result.size)
        }

    private fun success(version: RepositoryVersionToken? = null): JsonObject =
        JsonObject().also {
            it.addProperty("success", true)
            it.add("messages", JsonArray().also { messages -> version?.let { v -> messages.add(repoVersionMessage(v)) } })
        }

    private fun failure(message: String): JsonObject = failure("Error", message, emptyMap())

    private fun failure(
        kind: String,
        message: String,
        data: Map<String, String>,
    ): JsonObject =
        JsonObject().also {
            it.addProperty("success", false)
            it.add("messages", JsonArray().also { messages -> messages.add(message(kind, message, data)) })
        }

    private fun message(
        kind: String,
        message: String,
        data: Map<String, String>,
    ): JsonObject =
        JsonObject().also { error ->
            error.addProperty("kind", kind)
            error.addProperty("message", message)
            if (data.isNotEmpty()) {
                error.add("data", JsonObject().also { d -> data.forEach { (k, v) -> d.addProperty(k, v) } })
            }
        }

    private fun repoVersionMessage(version: RepositoryVersionToken): JsonObject {
        val numericVersion = version.token.substringAfterLast("-").toLongOrNull() ?: 0L
        return JsonObject().also {
            it.addProperty("kind", "RepoVersion")
            it.add(
                "data",
                JsonObject().also { data ->
                    data.addProperty("version", numericVersion)
                },
            )
        }
    }

    private fun HttpExchange.sendJson(
        statusCode: Int,
        body: JsonObject,
    ) {
        sendJson(statusCode, gson.toJson(body))
    }

    private fun HttpExchange.sendJson(
        statusCode: Int,
        body: JsonArray,
    ) {
        sendJson(statusCode, gson.toJson(body))
    }

    private fun HttpExchange.sendJson(
        statusCode: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(statusCode, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.queryParam(name: String): String? = queryParams()[name]

    private fun HttpExchange.requiredQueryParam(name: String): String =
        queryParam(name) ?: throw IllegalArgumentException("Missing query parameter: $name")

    private fun HttpExchange.queryParams(): Map<String, String> =
        requestURI.rawQuery
            ?.split("&")
            ?.filter { it.isNotBlank() }
            ?.associate {
                val parts = it.split("=", limit = 2)
                val key = URLDecoder.decode(parts[0], Charsets.UTF_8)
                val value = URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
                key to value
            } ?: emptyMap()
}
