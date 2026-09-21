package org.walnut.playground.springai

import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class RagChatRequest(val question: String, val topK: Int = 3)
data class RagChatResponse(val answer: String, val sources: List<RagSource>)
data class RagSource(
    val reference: Int,
    val chunkId: String,
    val text: String,
    val metadata: Map<String, Any>,
    val score: Double?,
)

@RestController
@Profile("vectors")
class RagChatController(private val ragChatService: RagChatService) {
    @PostMapping("/api/rag/chat")
    fun chat(@RequestBody request: RagChatRequest): RagChatResponse =
        ragChatService.chat(request)
}
