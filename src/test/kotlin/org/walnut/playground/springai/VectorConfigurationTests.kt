package org.walnut.playground.springai

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.deepseek.DeepSeekChatModel
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.SearchRequest
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
    ],
)
@ActiveProfiles("vectors")
class VectorConfigurationTests {
    @MockitoBean
    lateinit var vectorStore: VectorStore

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun resetChatCapture() {
        chatRequest.set("")
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
    fun ragEndpointSendsRetrievedTextAndReturnsRealSources() {
        val text = "Submit travel expenses within 30 days. Keep {receipt}."
        val document = Document("chunk-1", text, mapOf("source" to "policy", "documentId" to "document-1"))
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenReturn(listOf(document))

        val response = ragPost("""{"question":"When must I submit expenses?","topK":2}""")

        assertEquals(200, response.statusCode())
        val result = objectMapper.readTree(response.body())
        assertEquals("Submit within 30 days. [1]", result["answer"].asString())
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
    }

    @Test
    fun ragWithoutDocumentsDoesNotCallDeepSeek() {
        val response = ragPost("""{"question":"When must I submit expenses?"}""")
        assertEquals(200, response.statusCode())
        val result = objectMapper.readTree(response.body())
        assertTrue(result["sources"].isEmpty)
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
            """{}""",
        ).forEach { json -> assertEquals(400, ragPost(json).statusCode()) }
        verifyNoInteractions(vectorStore)
        assertEquals("", chatRequest.get())
    }

    @Test
    fun ragRejectsEmptyModelReply() {
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java)))
            .thenReturn(listOf(Document("Submit expenses within 30 days.")))
        assertEquals(502, ragPost("""{"question":"empty-provider-reply"}""").statusCode())
    }

    @Test
    fun ragDoesNotHideRetrievalFailure() {
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java)))
            .thenThrow(IllegalStateException("Vector database unavailable"))
        assertEquals(500, ragPost("""{"question":"hello"}""").statusCode())
        assertEquals("", chatRequest.get())
    }

    private fun ragPost(json: String): HttpResponse<String> {
        val port = context.environment.getRequiredProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/rag/chat"))
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
