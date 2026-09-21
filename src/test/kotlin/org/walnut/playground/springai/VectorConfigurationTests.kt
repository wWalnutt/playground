package org.walnut.playground.springai

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.deepseek.DeepSeekChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [SpringAiApplication::class],
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
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

    companion object {
        private val requestBody = AtomicReference("")
        private val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/embed") { exchange ->
                requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
                val vector = List(1024) { "0.5" }.joinToString(",")
                val body = """{"model":"bge-m3","embeddings":[[$vector]]}""".toByteArray()
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
        }

        @JvmStatic
        @AfterAll
        fun stopProvider() {
            provider.stop(0)
        }
    }
}
