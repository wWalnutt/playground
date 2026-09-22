package org.walnut.playground.springai

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.deepseek.DeepSeekChatModel
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import java.time.Instant
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [SpringAiApplication::class],
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
        "spring.datasource.url=jdbc:h2:mem:vector-config",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.ai.deepseek.api-key=test-key",
        "app.retrieval.similarity-threshold=0.6",
    ],
)
@ActiveProfiles("vectors")
class VectorConfigurationTests {
    @MockitoBean
    lateinit var vectorStore: VectorStore

    @MockitoBean
    lateinit var documents: KnowledgeDocumentRepository

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun resetChatCapture() {
        chatRequest.set("")
        requestBody.set("")
    }

    @Test
    fun keepsDeepSeekChatAndUsesOllamaForEmbeddings() {
        assertIs<DeepSeekChatModel>(context.getBean(ChatModel::class.java))
        assertIs<OllamaEmbeddingModel>(context.getBean(EmbeddingModel::class.java))
        assertNotNull(context.getBean(DataSource::class.java))
        val result = context.getBean(KnowledgeService::class.java).embed("hello")
        assertEquals(1024, result.dimensions)
        assertTrue(requestBody.get().contains("bge-m3"))
        assertTrue(requestBody.get().contains("hello"))
    }

    @Test
    fun uploadsReferenceFileThroughMultipartEndpoint() {
        var stored = emptyList<Document>()
        doAnswer { invocation ->
            stored = invocation.getArgument(3)
            null
        }.`when`(documents).index(any(UUID::class.java) ?: UUID(0, 0), anyString(),
            any(KnowledgeUploadType::class.java) ?: KnowledgeUploadType.TEXT, anyList())
        val text = "差旅报销须在30天内提交。"
        val response = upload("policy.md", text.toByteArray(Charsets.UTF_8))
        assertEquals(200, response.statusCode())
        val result = objectMapper.readTree(response.body())
        assertEquals(1, result["chunksIndexed"].asInt())
        assertEquals(text, stored.single().text)
        assertEquals("policy.md", stored.single().metadata["source"])
        assertEquals(result["documentId"].asString(), stored.single().metadata["documentId"])
        assertEquals("", chatRequest.get())
    }

    @Test
    fun rejectsInvalidUploadsOverHttp() {
        assertEquals(415, upload("policy.pdf", "text".toByteArray()).statusCode())
        assertEquals(400, upload("policy.txt", byteArrayOf()).statusCode())
        assertEquals(400, upload("policy.txt", byteArrayOf(0xff.toByte())).statusCode())
        assertEquals(400, upload("policy.txt", "a".repeat(20001).toByteArray()).statusCode())
        assertEquals(413, upload("policy.txt", ByteArray(65537) { 65 }).statusCode())
        verifyNoInteractions(vectorStore, documents)
        assertEquals("", chatRequest.get())
    }

    @Test
    fun listsViewsAndDeletesDocumentsWithoutCallingModels() {
        val id = UUID.randomUUID()
        val summary = KnowledgeDocumentSummary(
            id.toString(), "policy.md", KnowledgeUploadType.FILE, Instant.parse("2026-01-01T00:00:00Z"),
            1, KnowledgeDocumentStatus.READY,
        )
        `when`(documents.list(0, 10)).thenReturn(KnowledgeDocumentPage(listOf(summary), 0, 10, 1, 1))
        `when`(documents.details(id)).thenReturn(
            KnowledgeDocumentDetails(summary, listOf(KnowledgeChunk(UUID.randomUUID().toString(), 0, "policy text"))),
        )
        val page = managementRequest("")
        assertEquals(200, page.statusCode())
        val pageJson = objectMapper.readTree(page.body())
        assertEquals(1, pageJson["totalElements"].asInt())
        assertEquals("FILE", pageJson["items"][0]["uploadType"].asString())
        assertEquals("READY", pageJson["items"][0]["status"].asString())
        assertEquals("2026-01-01T00:00:00Z", pageJson["items"][0]["uploadedAt"].asString())
        val detail = managementRequest("/$id")
        assertEquals(200, detail.statusCode())
        assertEquals("policy text", objectMapper.readTree(detail.body())["chunks"][0]["text"].asString())
        val deleted = managementRequest("/$id", "DELETE")
        assertEquals(204, deleted.statusCode())
        assertEquals("", deleted.body())
        verify(documents).delete(id)
        verifyNoInteractions(vectorStore)
        assertEquals("", chatRequest.get())
        assertEquals("", requestBody.get())
    }

    @Test
    fun rejectsMalformedDocumentManagementRequestsBeforeDatabaseAccess() {
        listOf("?page=-1", "?page=oops", "?size=0", "?size=101", "/bad-id", "/1-1-1-1-1").forEach {
            assertEquals(400, managementRequest(it).statusCode())
        }
        assertEquals(400, managementRequest("/bad-id", "DELETE").statusCode())
        verifyNoInteractions(documents, vectorStore)
        assertEquals("", chatRequest.get())
        assertEquals("", requestBody.get())
    }

    @Test
    fun preservesMissingConflictAndDatabaseFailureStatuses() {
        val missing = UUID.randomUUID()
        val processing = UUID.randomUUID()
        `when`(documents.details(missing)).thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND))
        org.mockito.Mockito.doThrow(ResponseStatusException(HttpStatus.NOT_FOUND)).`when`(documents).delete(missing)
        org.mockito.Mockito.doThrow(ResponseStatusException(HttpStatus.CONFLICT)).`when`(documents).delete(processing)
        `when`(documents.list(0, 10)).thenThrow(IllegalStateException("Database unavailable"))
        assertEquals(404, managementRequest("/$missing").statusCode())
        assertEquals(404, managementRequest("/$missing", "DELETE").statusCode())
        assertEquals(409, managementRequest("/$processing", "DELETE").statusCode())
        assertEquals(500, managementRequest("").statusCode())
        verifyNoInteractions(vectorStore)
        assertEquals("", chatRequest.get())
        assertEquals("", requestBody.get())
    }

    private fun managementRequest(suffix: String, method: String = "GET"): HttpResponse<String> {
        val port = context.environment.getRequiredProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/knowledge/documents$suffix"))
            .method(method, HttpRequest.BodyPublishers.noBody()).build()
        return HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
    }

    private fun upload(filename: String, bytes: ByteArray): HttpResponse<String> {
        val boundary = "reference-file-boundary"
        val body = (
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray()
        val port = context.environment.getRequiredProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/knowledge/documents/upload"))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return HttpClient.newHttpClient().use {
            it.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    @Test
    fun ragEndpointSendsRetrievedTextAndReturnsRealSources() {
        val text = "Submit travel expenses within 30 days. Keep {receipt}."
        val document = Document.builder().id("chunk-1").text(text)
            .metadata(mapOf("source" to "policy", "documentId" to "document-1")).score(0.8).build()
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenReturn(listOf(document))

        val response = ragPost("""{"question":"When must I submit expenses?","topK":2}""")

        assertEquals(200, response.statusCode())
        val result = objectMapper.readTree(response.body())
        assertEquals("Submit within 30 days. [1]", result["answer"].asString())
        assertTrue(result["modelCalled"].asBoolean())
        assertEquals(0.6, result["diagnostics"]["similarityThreshold"].asDouble())
        assertEquals(1, result["sources"].size())
        val source = result["sources"][0]
        assertEquals(1, source["reference"].asInt())
        assertEquals("chunk-1", source["chunkId"].asString())
        assertEquals(text, source["text"].asString())
        assertEquals("policy", source["metadata"]["source"].asString())
        assertEquals("document-1", source["metadata"]["documentId"].asString())
        val prompt = objectMapper.readTree(chatRequest.get())["messages"]
        assertEquals("system", prompt[0]["role"].asString())
        assertTrue(prompt[0]["content"].asString().contains("using only"))
        assertEquals("user", prompt[1]["role"].asString())
        assertTrue(prompt[1]["content"].asString().contains("[1]\n$text"))
        assertEquals("When must I submit expenses?", prompt[2]["content"].asString())
        val captor = ArgumentCaptor.forClass(SearchRequest::class.java)
        verify(vectorStore).similaritySearch(captor.capture())
        assertEquals("When must I submit expenses?", captor.value.query)
        assertEquals(2, captor.value.topK)
        assertEquals(0.0, captor.value.similarityThreshold)
    }

    @Test
    fun ragWithoutDocumentsDoesNotCallDeepSeek() {
        val response = ragPost("""{"question":"When must I submit expenses?"}""")
        assertEquals(200, response.statusCode())
        val result = objectMapper.readTree(response.body())
        assertTrue(result["sources"].isEmpty)
        assertTrue(!result["modelCalled"].asBoolean())
        assertEquals(0, result["diagnostics"]["candidateCount"].asInt())
        assertTrue(result["answer"].asString().isNotBlank())
        assertEquals("", chatRequest.get())
        val captor = ArgumentCaptor.forClass(SearchRequest::class.java)
        verify(vectorStore).similaritySearch(captor.capture())
        assertEquals(3, captor.value.topK)
    }

    @Test
    fun ragRejectsInvalidInputBeforeRetrieval() {
        listOf(
            """{"question":" "}""",
            """{"question":"${"x".repeat(2001)}"}""",
            """{"question":"hello","topK":0}""",
            """{"question":"hello","topK":21}""",
            """{"question":null}""",
            """{"question":"hello","topK":null}""",
            """{"question":"hello","similarityThreshold":-0.1}""",
            """{"question":"hello","similarityThreshold":1.1}""",
            """{}""",
        ).forEach { json -> assertEquals(400, ragPost(json).statusCode()) }
        verifyNoInteractions(vectorStore)
        assertEquals("", chatRequest.get())
    }

    @Test
    fun ragRejectsEmptyModelReply() {
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java)))
            .thenReturn(listOf(Document.builder().text("Submit expenses within 30 days.").score(0.8).build()))
        assertEquals(502, ragPost("""{"question":"empty-provider-reply"}""").statusCode())
    }

    @Test
    fun ragDoesNotHideRetrievalFailure() {
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java)))
            .thenThrow(IllegalStateException("Vector database unavailable"))
        assertEquals(500, ragPost("""{"question":"hello"}""").statusCode())
        assertEquals("", chatRequest.get())
    }

    @Test
    fun ragDoesNotHideModelFailure() {
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java)))
            .thenReturn(listOf(Document.builder().text("Usable evidence").score(0.8).build()))
        assertEquals(500, ragPost("""{"question":"provider-failure"}""").statusCode())
        assertTrue(chatRequest.get().contains("provider-failure"))
    }

    @Test
    fun bothSearchEndpointsAndRagShareThresholdPoolAndAcceptedEvidence() {
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenReturn(
            listOf(
                Document.builder().id("rejected").text("REJECTED_SECRET_TEXT").score(0.4).build(),
                Document.builder().id("accepted-1").text("Submit travel expenses within 30 days.").score(0.7)
                    .metadata(mapOf("source" to "policy")).build(),
                Document.builder().id("blank").text(" \n").score(0.9).build(),
                Document.builder().id("accepted-2").text("Keep the receipt.").score(0.8).build(),
            ),
        )
        val json = """{"query":"expenses","topK":4,"similarityThreshold":0.7}"""
        val legacy = apiPost("/api/knowledge/search", json)
        val diagnostic = apiPost("/api/knowledge/search/diagnostics", json)
        assertEquals(200, legacy.statusCode())
        assertEquals(200, diagnostic.statusCode())
        assertEquals("", chatRequest.get())
        val legacyJson = objectMapper.readTree(legacy.body())
        val searchJson = objectMapper.readTree(diagnostic.body())
        assertTrue(legacyJson.isArray)
        assertEquals(legacyJson, searchJson["matches"])
        val details = searchJson["diagnostics"]
        assertEquals(4, details["topK"].asInt())
        assertEquals(0.7, details["similarityThreshold"].asDouble())
        assertEquals(4, details["candidateCount"].asInt())
        assertEquals(2, details["acceptedCount"].asInt())
        assertEquals(2, details["rejectedCount"].asInt())
        assertTrue(details["elapsedMs"].asLong() >= 0)
        assertEquals(4, details["candidates"].size())
        val rejected = details["candidates"][0]
        assertEquals("rejected", rejected["chunkId"].asString())
        assertTrue(rejected["source"].isNull)
        assertEquals(0.4, rejected["score"].asDouble())
        assertTrue(!rejected["accepted"].asBoolean())
        assertEquals("BELOW_THRESHOLD", rejected["reason"].asString())
        assertEquals("policy", details["candidates"][1]["source"].asString())
        assertEquals("ACCEPTED", details["candidates"][1]["reason"].asString())
        assertEquals("EMPTY_TEXT", details["candidates"][2]["reason"].asString())
        assertTrue(!diagnostic.body().contains("REJECTED_SECRET_TEXT"))
        assertTrue(!details.toString().contains("\"text\""))
        val rag = ragPost("""{"question":"expenses","topK":4,"similarityThreshold":0.7}""")
        assertEquals(200, rag.statusCode())
        val ragJson = objectMapper.readTree(rag.body())
        assertEquals(details["candidates"], ragJson["diagnostics"]["candidates"])
        assertTrue(ragJson["modelCalled"].asBoolean())
        assertEquals(1, ragJson["sources"][0]["reference"].asInt())
        assertEquals("accepted-1", ragJson["sources"][0]["chunkId"].asString())
        assertEquals(2, ragJson["sources"][1]["reference"].asInt())
        assertEquals("accepted-2", ragJson["sources"][1]["chunkId"].asString())
        assertTrue(!chatRequest.get().contains("REJECTED_SECRET_TEXT"))
        val prompt = objectMapper.readTree(chatRequest.get())["messages"][1]["content"].asString()
        assertTrue(prompt.contains("[1]\nSubmit travel expenses"))
        assertTrue(prompt.contains("[2]\nKeep the receipt."))
        val captor = ArgumentCaptor.forClass(SearchRequest::class.java)
        verify(vectorStore, times(3)).similaritySearch(captor.capture())
        captor.allValues.forEach {
            assertEquals("expenses", it.query)
            assertEquals(4, it.topK)
            assertEquals(0.0, it.similarityThreshold)
        }
    }

    @Test
    fun allRejectedSkipsDeepSeekAndExplicitZeroOverridesConfiguredDefault() {
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenReturn(
            listOf(Document.builder().id("low").text("Low-scoring evidence").score(0.1).build()),
        )
        val refused = ragPost("""{"question":"expenses","similarityThreshold":null}""")
        assertEquals(200, refused.statusCode())
        val result = objectMapper.readTree(refused.body())
        assertTrue(!result["modelCalled"].asBoolean())
        assertTrue(result["sources"].isEmpty)
        assertEquals(1, result["diagnostics"]["rejectedCount"].asInt())
        assertEquals(0.6, result["diagnostics"]["similarityThreshold"].asDouble())
        assertEquals("", chatRequest.get())
        val search = apiPost("/api/knowledge/search/diagnostics", """{"query":"expenses","similarityThreshold":0}""")
        assertEquals(1, objectMapper.readTree(search.body())["matches"].size())
        assertEquals("", chatRequest.get())
        val baseline = ragPost("""{"question":"expenses","similarityThreshold":0}""")
        assertEquals(200, baseline.statusCode())
        assertTrue(objectMapper.readTree(baseline.body())["modelCalled"].asBoolean())
    }

    @Test
    fun searchEndpointsRejectInvalidInputBeforeDependencies() {
        listOf("/api/knowledge/search", "/api/knowledge/search/diagnostics").forEach { path ->
            listOf(
                """{"query":" "}""", """{"query":"${"x".repeat(2001)}"}""",
                """{"query":"query","topK":0}""", """{"query":"query","topK":21}""",
                """{"query":"query","similarityThreshold":-0.1}""",
                """{"query":"query","similarityThreshold":1.1}""",
                """{"query":null}""", """{"query":"query","topK":null}""",
            ).forEach { assertEquals(400, apiPost(path, it).statusCode()) }
        }
        verifyNoInteractions(vectorStore, documents)
        assertEquals("", requestBody.get())
        assertEquals("", chatRequest.get())
    }

    @Test
    fun missingScoresAndRetrievalFailuresAreNotSuccessfulEmptyResults() {
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenReturn(listOf(Document("unscored")))
        assertEquals(502, ragPost("""{"question":"query"}""").statusCode())
        listOf("/api/knowledge/search", "/api/knowledge/search/diagnostics").forEach {
            assertEquals(502, apiPost(it, """{"query":"query"}""").statusCode())
        }
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenThrow(IllegalStateException("upstream"))
        listOf("/api/knowledge/search", "/api/knowledge/search/diagnostics").forEach {
            assertEquals(500, apiPost(it, """{"query":"query"}""").statusCode())
        }
        assertEquals("", chatRequest.get())
    }

    private fun ragPost(json: String): HttpResponse<String> = apiPost("/api/rag/chat", json)

    private fun apiPost(path: String, json: String): HttpResponse<String> {
        val port = context.environment.getRequiredProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        return HttpClient.newHttpClient().use {
            it.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    companion object {
        private val requestBody = AtomicReference("")
        private val chatRequest = AtomicReference("")
        private val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/embed") { exchange ->
                requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
                val vector = List(1024) { "0.5" }.joinToString(",")
                val body = """{"model":"bge-m3","embeddings":[[$vector]]}""".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/chat/completions") { exchange ->
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                chatRequest.set(request)
                if ("provider-failure" in request) {
                    val error = """{"error":{"message":"Upstream failure","type":"invalid_request_error"}}""".toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(400, error.size.toLong())
                    exchange.responseBody.use { it.write(error) }
                    return@createContext
                }
                val reply = if ("empty-provider-reply" in request) "" else "Submit within 30 days. [1]"
                val body = """
                    {"id":"test","object":"chat.completion","created":1,"model":"deepseek-chat",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"$reply"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}
                """.trimIndent().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun ollamaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.ai.ollama.base-url") { "http://127.0.0.1:${provider.address.port}" }
            registry.add("spring.ai.deepseek.base-url") { "http://127.0.0.1:${provider.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stopProvider() {
            provider.stop(0)
        }
    }
}
