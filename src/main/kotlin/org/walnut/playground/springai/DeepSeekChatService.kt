package org.walnut.playground.springai

import org.springframework.ai.chat.client.ChatClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

@Service
class DeepSeekChatService(chatClientBuilder: ChatClient.Builder) {
    private val chatClient = chatClientBuilder.build()

    fun chat(message: String): String {
        validate(message)
        val reply = chatClient.prompt().user(message).call().content()
        if (reply.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek returned an empty reply")
        }
        return reply
    }

    fun stream(message: String): Flux<ChatEvent> {
        validate(message)
        return Flux.just(ChatStreaming.event("status", ChatStatus("generating")))
            .concatWith(Flux.defer {
                ChatStreaming.modelEvents(chatClient.prompt().user(message).stream().chatResponse())
            }.subscribeOn(Schedulers.boundedElastic()))
    }

    private fun validate(message: String) {
        if (message.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank")
        }
    }
}
