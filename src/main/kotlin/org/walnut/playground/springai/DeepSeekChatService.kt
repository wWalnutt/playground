package org.walnut.playground.springai

import org.springframework.ai.chat.client.ChatClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class DeepSeekChatService(chatClientBuilder: ChatClient.Builder) {
    private val chatClient = chatClientBuilder.build()

    fun chat(message: String): String {
        if (message.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank")
        }
        val reply = chatClient.prompt().user(message).call().content()
        if (reply.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek returned an empty reply")
        }
        return reply
    }
}
