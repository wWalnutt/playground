package org.walnut.playground.springai

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

data class DeepSeekChatRequest(val message: String)
data class DeepSeekChatResponse(val reply: String)

@RestController
@RequestMapping("/api/chat")
class DeepSeekChatController(private val chatService: DeepSeekChatService) {
    @PostMapping
    fun chat(@RequestBody request: DeepSeekChatRequest): DeepSeekChatResponse =
        DeepSeekChatResponse(chatService.chat(request.message))

    @PostMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestBody request: DeepSeekChatRequest): ResponseEntity<Flux<ChatEvent>> =
        ChatStreaming.response(chatService.stream(request.message))
}
