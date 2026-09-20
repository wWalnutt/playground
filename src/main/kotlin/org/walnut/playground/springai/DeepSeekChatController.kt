package org.walnut.playground.springai

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DeepSeekChatRequest(val message: String)
data class DeepSeekChatResponse(val reply: String)

@RestController
@RequestMapping("/api/chat")
class DeepSeekChatController(private val chatService: DeepSeekChatService) {
    @PostMapping
    fun chat(@RequestBody request: DeepSeekChatRequest): DeepSeekChatResponse =
        DeepSeekChatResponse(chatService.chat(request.message))
}
