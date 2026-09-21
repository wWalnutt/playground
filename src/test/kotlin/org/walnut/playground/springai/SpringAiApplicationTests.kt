package org.walnut.playground.springai

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.deepseek.DeepSeekChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [SpringAiApplication::class],
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class SpringAiApplicationTests {
    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun loadsDeepSeekWithoutDatabaseOrOtherDemos() {
        assertTrue("springai" in context.environment.activeProfiles)
        assertIs<DeepSeekChatModel>(context.getBean(ChatModel::class.java))
        assertTrue(context.getBeansOfType(DataSource::class.java).isEmpty())
        assertTrue(context.beanDefinitionNames.none {
            context.getType(it)?.name?.let { name ->
                name.startsWith("org.walnut.playground.demo.") ||
                    name.startsWith("org.walnut.playground.DDD_FSM.")
            } == true
        })
    }

    @Test
    fun servesChatPageAndAssets() {
        val port = context.environment.getRequiredProperty("local.server.port")
        HttpClient.newHttpClient().use { client ->
            listOf(
                "/" to "id=\"chat-form\"",
                "/" to "data-view=\"chat\"",
                "/" to "data-view=\"rag\"",
                "/" to "data-view=\"upload\"",
                "/" to "id=\"rag-messages\"",
                "/" to "id=\"upload-form\"",
                "/" to "id=\"text-upload-form\"",
                "/" to "id=\"document-source\"",
                "/" to "id=\"document-text\"",
                "/chat.js" to "\"/api/rag/chat\" : \"/api/chat\"",
                "/chat.js" to "/api/knowledge/documents/upload",
                "/chat.css" to ".bubble",
            ).forEach { (path, content) ->
                val response = client.send(
                    HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains(content))
            }
        }
    }

    @Test
    fun sendsMessageToDeepSeekAndReturnsReply() {
        val response = post("""{"message":"Hello DeepSeek"}""")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Hello from DeepSeek"))
        assertTrue(providerRequest.get().contains("Hello DeepSeek"))
        assertTrue(providerRequest.get().contains("deepseek-chat"))
    }

    @Test
    fun rejectsBlankMessageWithoutCallingProvider() {
        providerRequest.set("")
        assertEquals(400, post("""{"message":"   "}""").statusCode())
        assertEquals("", providerRequest.get())
    }

    @Test
    fun surfacesEmptyProviderReply() {
        assertEquals(502, post("""{"message":"empty-reply"}""").statusCode())
    }

    private fun post(json: String): HttpResponse<String> {
        val port = context.environment.getRequiredProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        return HttpClient.newHttpClient().use {
            it.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    companion object {
        private val providerRequest = AtomicReference("")
        private val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/chat/completions") { exchange ->
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                providerRequest.set(request)
                val reply = if ("empty-reply" in request) "" else "Hello from DeepSeek"
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
        fun providerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.ai.deepseek.base-url") { "http://127.0.0.1:${provider.address.port}" }
            registry.add("spring.ai.deepseek.api-key") { "test-key" }
            registry.add("spring.ai.deepseek.chat.options.model") { "deepseek-chat" }
        }

        @JvmStatic
        @AfterAll
        fun stopProvider() {
            provider.stop(0)
        }
    }
}
