package org.walnut.playground.springai

import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class EmbeddingRequest(val text: String)
data class EmbeddingResponse(val model: String, val dimensions: Int, val vector: List<Float>)
data class KnowledgeDocumentRequest(val text: String, val source: String = "inline")
data class KnowledgeDocumentResponse(val documentId: String, val chunksIndexed: Int)
data class KnowledgeSearchRequest(val query: String, val topK: Int = 3)
data class KnowledgeMatch(
    val id: String,
    val text: String?,
    val metadata: Map<String, Any>,
    val score: Double?,
)

@RestController
@Profile("vectors")
class KnowledgeController(private val knowledgeService: KnowledgeService) {
    @PostMapping("/api/embeddings")
    fun embed(@RequestBody request: EmbeddingRequest): EmbeddingResponse =
        knowledgeService.embed(request.text)

    @PostMapping("/api/knowledge/documents")
    fun ingest(@RequestBody request: KnowledgeDocumentRequest): KnowledgeDocumentResponse =
        knowledgeService.ingest(request)

    @PostMapping("/api/knowledge/search")
    fun search(@RequestBody request: KnowledgeSearchRequest): List<KnowledgeMatch> =
        knowledgeService.search(request)
}
