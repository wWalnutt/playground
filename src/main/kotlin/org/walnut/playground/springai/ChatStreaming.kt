package org.walnut.playground.springai

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration

typealias ChatEvent = ServerSentEvent<Any>

data class ChatStatus(val stage: String)
data class ChatDelta(val text: String)
data class ChatDone(val modelCalled: Boolean)
data class ChatStreamError(val code: String, val message: String)
data class ChatSources(val sources: List<RagSource>, val diagnostics: RetrievalDiagnostics)

internal class IncompleteChatException : RuntimeException("Model stream did not finish successfully")
internal class EmptyChatException : RuntimeException("Model stream contained no answer")
internal class ChatTimeoutException : RuntimeException("Chat stream exceeded its deadline")

internal object ChatStreaming {
    private val logger = LoggerFactory.getLogger(ChatStreaming::class.java)
    val cancellationContextKey = Any()

    fun event(name: String, data: Any): ChatEvent =
        ServerSentEvent.builder<Any>().event(name).data(data).build()

    fun modelEvents(responses: Flux<ChatResponse>): Flux<ChatEvent> = Flux.defer {
        var hasText = false
        var stopped = false
        val cancelled = Sinks.empty<Void>()
        responses.contextWrite { it.put(cancellationContextKey, cancelled.asMono()) }
            .doFinally { cancelled.tryEmitEmpty() }
            .handle<ChatEvent> { response, sink ->
                val generation = response.result
                val reason = generation?.metadata?.finishReason.orEmpty()
                if (reason.isNotBlank()) {
                    if (!reason.equals("stop", ignoreCase = true)) {
                        sink.error(IncompleteChatException())
                        return@handle
                    }
                    stopped = true
                }
                val text = generation?.output?.text.orEmpty()
                if (text.isNotEmpty()) {
                    hasText = hasText || text.isNotBlank()
                    sink.next(event("delta", ChatDelta(text)))
                }
            }.concatWith(Mono.defer {
                when {
                    !stopped -> Mono.error(IncompleteChatException())
                    !hasText -> Mono.error(EmptyChatException())
                    else -> Mono.just(event("done", ChatDone(true)))
                }
            })
    }

    fun response(events: Flux<ChatEvent>): ResponseEntity<Flux<ChatEvent>> =
        ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .cacheControl(CacheControl.noCache())
            .header("X-Accel-Buffering", "no")
            .body(lifecycle(events))

    internal fun lifecycle(
        events: Flux<ChatEvent>,
        lifetime: Duration = Duration.ofSeconds(120),
        heartbeat: Duration = Duration.ofSeconds(5),
    ): Flux<ChatEvent> {
        // One subscription to the model; heartbeats stop with it and expose idle disconnects.
        val timed = events.takeUntilOther(
            Mono.delay(lifetime).flatMap<Any> { Mono.error(ChatTimeoutException()) },
        )
        return timed.publish { shared ->
            Flux.merge(
                1,
                shared,
                Flux.interval(heartbeat)
                    .onBackpressureDrop()
                    .map { ServerSentEvent.builder<Any>().comment("keep-alive").build() }
                    .takeUntilOther(shared.ignoreElements()),
            )
        }.onErrorResume { failure ->
            logger.warn("Chat streaming failed", failure)
            val error = when (failure) {
                is ChatTimeoutException -> ChatStreamError("timeout", "The request timed out. Please try again.")
                is IncompleteChatException -> ChatStreamError("incomplete_response", "The model response was incomplete.")
                is EmptyChatException -> ChatStreamError("empty_response", "The model returned an empty response.")
                else -> ChatStreamError("stream_failed", "Unable to complete the response. Please try again.")
            }
            Mono.just(event("error", error))
        }
    }
}

@Configuration
class ChatStreamingConfiguration {
    @Bean
    fun chatStreamCancellation() = WebClientCustomizer { builder ->
        builder.filter { request, next ->
            Mono.deferContextual { context ->
                val cancellation = context.getOrEmpty<Mono<Void>>(ChatStreaming.cancellationContextKey).orElse(null)
                if (cancellation == null) {
                    next.exchange(request)
                } else {
                    // DeepSeek's windowed tool-call decoder can retain an idle window after
                    // cancellation. Also cancel the raw HTTP body so its connection is released.
                    next.exchange(request).takeUntilOther(cancellation).map { response ->
                        response.mutate().body { it.takeUntilOther(cancellation) }.build()
                    }
                }
            }
        }
    }

    @Bean
    fun chatStreamExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 8
        maxPoolSize = 32
        queueCapacity = 128
        setThreadNamePrefix("chat-stream-write-")
    }

    @Bean
    fun chatStreamingMvcConfigurer(chatStreamExecutor: ThreadPoolTaskExecutor) = object : WebMvcConfigurer {
        override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
            // MVC performs blocking SSE writes here, never on the provider's reactor event loop.
            configurer.setTaskExecutor(chatStreamExecutor)
            configurer.setDefaultTimeout(125_000)
        }
    }
}
