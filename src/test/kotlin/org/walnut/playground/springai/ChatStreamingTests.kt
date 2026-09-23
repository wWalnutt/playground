package org.walnut.playground.springai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [SpringAiApplication::class, ChatStreamingTests.RagTestConfiguration::class],
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ChatStreamingTests {
    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var vectorStore: VectorStore

    @BeforeEach
    fun reset() {
        reset(vectorStore)
        providerCalls.set(0)
        providerRequest.set("")
        releaseProvider = CountDownLatch(1)
        providerCompleted = CountDownLatch(1)
        providerDisconnected = CountDownLatch(1)
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenReturn(
            listOf(
                Document.builder().id("accepted").text("Accepted evidence").score(0.9)
                    .metadata(mapOf("source" to "policy")).build(),
                Document.builder().id("rejected").text("Rejected evidence").score(0.2).build(),
            ),
        )
    }

    @Test
    fun deliversFirstDeltaBeforeProviderCompletesAndPreservesUnicodeAndNewlines() {
        HttpClient.newHttpClient().use { client ->
            val response = client.send(request("/api/chat/stream", """{"message":"incremental"}"""),
                HttpResponse.BodyHandlers.ofInputStream())
            assertEquals(200, response.statusCode())
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))
            assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""))
            assertEquals("no", response.headers().firstValue("X-Accel-Buffering").orElse(""))
            response.body().bufferedReader().use { reader ->
                try {
                    assertEquals("generating", readEvent(reader).second["stage"].asString())
                    val first = readEvent(reader)
                    assertEquals("delta", first.first)
                    assertEquals("你好\n", first.second["text"].asString())
                    assertEquals(1L, providerCompleted.count, "The upstream must still be waiting")
                    releaseProvider.countDown()
                    val remaining = readEvents(reader)
                    assertEquals(listOf("delta", "done"), remaining.map { it.first })
                    assertEquals("world 🌍", remaining[0].second["text"].asString())
                    assertTrue(remaining[1].second["modelCalled"].asBoolean())
                } finally {
                    releaseProvider.countDown()
                }
            }
        }
        assertEquals(1, providerCalls.get())
        assertTrue(mapper.readTree(providerRequest.get())["stream"].asBoolean())
    }

    @Test
    fun ragSendsAcceptedSourcesBeforeLiveTextAndSharesJsonPrompt() {
        val events = postEvents("/api/rag/chat/stream", """{"question":"question","topK":2}""")
        assertEquals(listOf("status", "sources", "status", "delta", "done"), events.map { it.first })
        assertEquals("retrieving", events[0].second["stage"].asString())
        assertEquals("generating", events[2].second["stage"].asString())
        val sources = events[1].second
        assertFalse(sources.has("modelCalled"))
        assertEquals(1, sources["sources"].size())
        assertEquals("accepted", sources["sources"][0]["chunkId"].asString())
        assertEquals(1, sources["diagnostics"]["rejectedCount"].asInt())
        assertTrue(events.last().second["modelCalled"].asBoolean())
        val streamingMessages = mapper.readTree(providerRequest.get())["messages"]
        assertTrue(providerRequest.get().contains("Accepted evidence"))
        assertFalse(providerRequest.get().contains("Rejected evidence"))

        val response = post("/api/rag/chat", """{"question":"question","topK":2}""")
        assertEquals(200, response.statusCode())
        assertEquals(streamingMessages, mapper.readTree(providerRequest.get())["messages"])
        assertTrue(mapper.readTree(response.body())["modelCalled"].asBoolean())
    }

    @Test
    fun ragStatusPrecedesBlockingRetrievalAndDeltasPrecedeProviderCompletion() {
        val releaseRetrieval = CountDownLatch(1)
        val retrievalThread = AtomicReference("")
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenAnswer {
            retrievalThread.set(Thread.currentThread().name)
            check(releaseRetrieval.await(10, TimeUnit.SECONDS))
            listOf(Document.builder().id("accepted").text("Evidence").score(0.9).build())
        }
        HttpClient.newHttpClient().use { client ->
            try {
                val response = client.send(request("/api/rag/chat/stream", """{"question":"incremental"}"""),
                    HttpResponse.BodyHandlers.ofInputStream())
                assertEquals(200, response.statusCode())
                response.body().bufferedReader().use { reader ->
                    val status = readEvent(reader)
                    assertEquals("status", status.first)
                    assertEquals("retrieving", status.second["stage"].asString())
                    assertEquals(0, providerCalls.get())
                    releaseRetrieval.countDown()
                    assertEquals("sources", readEvent(reader).first)
                    assertEquals("generating", readEvent(reader).second["stage"].asString())
                    assertEquals("你好\n", readEvent(reader).second["text"].asString())
                    assertEquals(1L, providerCompleted.count)
                    releaseProvider.countDown()
                    assertEquals(listOf("delta", "done"), readEvents(reader).map { it.first })
                }
                assertTrue(retrievalThread.get().startsWith("boundedElastic-"))
            } finally {
                releaseRetrieval.countDown()
                releaseProvider.countDown()
            }
        }
    }

    @Test
    fun thresholdRefusalHasOneDeltaNoModelCallAndFinalFalse() {
        val events = postEvents("/api/rag/chat/stream", """{"question":"question","similarityThreshold":1}""")
        assertEquals(listOf("status", "sources", "delta", "done"), events.map { it.first })
        assertEquals(0, events[1].second["sources"].size())
        assertEquals(0, events[1].second["diagnostics"]["acceptedCount"].asInt())
        assertFalse(events[1].second.has("modelCalled"))
        assertTrue(events[2].second["text"].asString().isNotBlank())
        assertFalse(events.last().second["modelCalled"].asBoolean())
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun rejectsInvalidInputsBeforeOpeningStream() {
        listOf(
            "/api/chat/stream" to """{"message":" "}""",
            "/api/chat/stream" to """{}""",
            "/api/rag/chat/stream" to """{"question":""}""",
            "/api/rag/chat/stream" to """{"question":"x","topK":0}""",
            "/api/rag/chat/stream" to """{"question":"x","similarityThreshold":1.1}""",
            "/api/rag/chat/stream" to """{"question":"${"x".repeat(2001)}"}""",
        ).forEach { (path, json) ->
            val response = post(path, json)
            assertEquals(400, response.statusCode(), path)
            assertFalse(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))
        }
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun incompleteFilteredEmptyAndFailedProviderStreamsNeverSendDoneOrRetry() {
        listOf(
            "truncated" to "incomplete_response",
            "length" to "incomplete_response",
            "content-filter" to "incomplete_response",
            "empty" to "empty_response",
            "broken" to "stream_failed",
            "provider-error" to "stream_failed",
        ).forEachIndexed { index, (scenario, code) ->
            val events = postEvents("/api/chat/stream", """{"message":"$scenario"}""")
            assertEquals("status", events.first().first)
            assertEquals("error", events.last().first, scenario)
            assertEquals(code, events.last().second["code"].asString(), scenario)
            assertEquals(1, events.count { it.first == "error" })
            assertTrue(events.none { it.first == "done" })
            assertFalse(events.last().second.toString().contains("secret"))
            assertEquals(index + 1, providerCalls.get(), "Streaming must not retry")
        }
    }

    @Test
    fun retrievalFailureIsAnErrorAfterRetrievingStatusWithoutModelCall() {
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java)))
            .thenThrow(IllegalStateException("secret embedding detail"))
        val events = postEvents("/api/rag/chat/stream", """{"question":"question"}""")
        assertEquals(listOf("status", "error"), events.map { it.first })
        assertEquals("stream_failed", events.last().second["code"].asString())
        assertFalse(events.last().second.toString().contains("secret"))
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun browserDisconnectClosesProviderConnection() {
        HttpClient.newHttpClient().use { client ->
            val response = client.send(request("/api/chat/stream", """{"message":"disconnect"}"""),
                HttpResponse.BodyHandlers.ofInputStream())
            response.body().bufferedReader().use { reader ->
                assertEquals("status", readEvent(reader).first)
                assertEquals("delta", readEvent(reader).first)
            }
            assertTrue(providerDisconnected.await(10, TimeUnit.SECONDS), "Provider connection was not cancelled")
        }
    }

    @Test
    fun heartbeatDetectsDisconnectWhileProviderIsIdle() {
        val port = context.environment.getRequiredProperty("local.server.port").toInt()
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 15_000
            socket.setSoLinger(true, 0)
            val json = """{"message":"idle-disconnect"}"""
            socket.getOutputStream().write(
                ("POST /api/chat/stream HTTP/1.1\r\nHost: localhost\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${json.length}\r\n\r\n$json").toByteArray(),
            )
            socket.getOutputStream().flush()
            val reader = socket.getInputStream().bufferedReader()
            var line: String
            do {
                line = checkNotNull(reader.readLine()) { "Stream ended before first delta" }
            } while (!line.contains("\"text\":\"first\""))
            do {
                line = checkNotNull(reader.readLine()) { "Stream ended before heartbeat" }
            } while (!line.contains(":keep-alive"))
        }
        assertTrue(providerDisconnected.await(15, TimeUnit.SECONDS), "Idle provider was not cancelled")
    }

    @Test
    fun lifetimeCancelsEvenAnActiveStreamAndEmitsOnlyTimeoutError() {
        val cancelled = CountDownLatch(1)
        val upstream = Flux.interval(Duration.ofMillis(10))
            .map { ChatStreaming.event("delta", ChatDelta("text")) }
            .doOnCancel { cancelled.countDown() }
        val events = ChatStreaming.lifecycle(upstream, Duration.ofMillis(100), Duration.ofMillis(20))
            .collectList().block(Duration.ofSeconds(5))!!
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        assertTrue(events.any { it.comment() == "keep-alive" })
        assertEquals("error", events.last().event())
        assertEquals("timeout", (events.last().data() as ChatStreamError).code)
        assertTrue(events.none { it.event() == "done" })
        assertEquals(1, events.count { it.event() == "error" })
    }

    @Test
    fun timeoutClosesIdleProviderHttpConnection() {
        val service = context.getBean(DeepSeekChatService::class.java)
        val events = ChatStreaming.lifecycle(service.stream("idle-disconnect"), Duration.ofSeconds(1))
            .collectList().block(Duration.ofSeconds(5))!!
        assertEquals("error", events.last().event())
        assertEquals("timeout", (events.last().data() as ChatStreamError).code)
        assertTrue(events.none { it.event() == "done" })
        assertTrue(providerDisconnected.await(3, TimeUnit.SECONDS), "Timed-out HTTP connection was not released")
    }

    @Test
    fun directCancellationDisposesIdleModelAndHeartbeat() {
        val cancelled = CountDownLatch(1)
        val subscription = ChatStreaming.lifecycle(Flux.never<ChatEvent>().doOnCancel { cancelled.countDown() })
            .subscribe()
        subscription.dispose()
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }

    private fun request(path: String, json: String): HttpRequest {
        val port = context.environment.getRequiredProperty("local.server.port")
        return HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Accept", if (path.endsWith("/stream")) "text/event-stream" else "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build()
    }

    private fun post(path: String, json: String): HttpResponse<String> =
        HttpClient.newHttpClient().use { it.send(request(path, json), HttpResponse.BodyHandlers.ofString()) }

    private fun postEvents(path: String, json: String): List<Pair<String, JsonNode>> {
        val response = post(path, json)
        assertEquals(200, response.statusCode(), response.body())
        return response.body().reader().buffered().use { readEvents(it) }
    }

    private fun readEvents(reader: BufferedReader): List<Pair<String, JsonNode>> =
        generateSequence { readEventOrNull(reader) }.toList()

    private fun readEvent(reader: BufferedReader): Pair<String, JsonNode> =
        checkNotNull(readEventOrNull(reader)) { "Unexpected EOF" }

    private fun readEventOrNull(reader: BufferedReader): Pair<String, JsonNode>? {
        var event = ""
        var data = ""
        while (true) {
            val line = reader.readLine() ?: return null
            when {
                line.startsWith("event:") -> event = line.substringAfter(':').trim()
                line.startsWith("data:") -> data += line.substringAfter(':').trimStart()
                line.isEmpty() && event.isNotEmpty() -> return event to mapper.readTree(data)
            }
        }
    }

    @TestConfiguration
    class RagTestConfiguration {
        @Bean
        fun vectorStore(): VectorStore = mock(VectorStore::class.java)

        @Bean
        fun knowledgeService(vectorStore: VectorStore) = KnowledgeService(
            mock(EmbeddingModel::class.java),
            KnowledgeRetrievalService(vectorStore, 0.6),
            mock(KnowledgeDocumentRepository::class.java),
        )

        @Bean
        fun ragChatService(knowledgeService: KnowledgeService, builder: ChatClient.Builder) =
            RagChatService(knowledgeService, builder)

        @Bean
        fun ragChatController(service: RagChatService) = RagChatController(service)
    }

    companion object {
        private val mapper = JsonMapper.builder().build()
        private val providerCalls = AtomicInteger()
        private val providerRequest = AtomicReference("")
        private var releaseProvider = CountDownLatch(1)
        private var providerCompleted = CountDownLatch(1)
        private var providerDisconnected = CountDownLatch(1)
        private val providerExecutor = Executors.newVirtualThreadPerTaskExecutor()
        private val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = providerExecutor
            createContext("/chat/completions") { exchange ->
                val completed = providerCompleted
                val disconnected = providerDisconnected
                val release = releaseProvider
                providerCalls.incrementAndGet()
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                providerRequest.set(request)
                val payload = mapper.readTree(request)
                val scenario = payload["messages"].last()["content"].asString()
                try {
                    if (!payload.path("stream").asBoolean()) {
                        exchange.responseHeaders.set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write(
                            """{"id":"test","model":"deepseek-chat","choices":[{"index":0,"message":{"role":"assistant","content":"answer [1]"},"finish_reason":"stop"}]}"""
                                .toByteArray(),
                        )
                    } else if (scenario == "provider-error") {
                        exchange.sendResponseHeaders(500, 0)
                        exchange.responseBody.write("secret provider detail".toByteArray())
                    } else {
                        exchange.responseHeaders.set("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        when (scenario) {
                            "incremental" -> {
                                chunk(exchange, "你好\n")
                                check(release.await(10, TimeUnit.SECONDS))
                                chunk(exchange, "world 🌍")
                            }
                            "disconnect" -> {
                                repeat(200) {
                                    chunk(exchange, "live ${"x".repeat(1024)}")
                                    Thread.sleep(50)
                                }
                            }
                            "idle-disconnect" -> {
                                chunk(exchange, "first")
                                repeat(400) {
                                    exchange.responseBody.write(": idle\n\n".toByteArray())
                                    exchange.responseBody.flush()
                                    Thread.sleep(50)
                                }
                            }
                            "empty" -> chunk(exchange, " \n\t")
                            else -> chunk(exchange, "answer [1]")
                        }
                        when (scenario) {
                            "truncated" -> Unit
                            "broken" -> writeSse(exchange, "{broken secret JSON")
                            else -> {
                                val reason = when (scenario) {
                                    "length" -> "length"
                                    "content-filter" -> "content_filter"
                                    else -> "stop"
                                }
                                chunk(exchange, "", reason)
                                writeSse(exchange, """{"id":"test","model":"deepseek-chat","choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""")
                                writeSse(exchange, "[DONE]")
                            }
                        }
                    }
                } catch (_: IOException) {
                    disconnected.countDown()
                } finally {
                    exchange.close()
                    completed.countDown()
                }
            }
            start()
        }

        private fun chunk(exchange: HttpExchange, text: String, finishReason: String? = null) {
            writeSse(exchange, mapper.writeValueAsString(mapOf(
                "id" to "test", "object" to "chat.completion.chunk", "created" to 1, "model" to "deepseek-chat",
                "choices" to listOf(mapOf(
                    "index" to 0,
                    "delta" to mapOf("role" to "assistant", "content" to text),
                    "finish_reason" to finishReason,
                )),
            )))
        }

        private fun writeSse(exchange: HttpExchange, data: String) {
            exchange.responseBody.write("data: $data\n\n".toByteArray(Charsets.UTF_8))
            exchange.responseBody.flush()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.ai.deepseek.base-url") { "http://127.0.0.1:${provider.address.port}" }
            registry.add("spring.ai.deepseek.api-key") { "test-key" }
        }

        @JvmStatic
        @AfterAll
        fun stopProvider() {
            releaseProvider.countDown()
            provider.stop(0)
            providerExecutor.shutdownNow()
        }
    }
}
