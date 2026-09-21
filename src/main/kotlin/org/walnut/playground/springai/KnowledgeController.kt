package org.walnut.playground.springai

import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile

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

    @PostMapping("/api/knowledge/documents/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestParam("file") file: MultipartFile): KnowledgeDocumentResponse =
        knowledgeService.ingestFile(file)

    @GetMapping("/api/knowledge/documents")
    fun documents(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ): KnowledgeDocumentPage = knowledgeService.listDocuments(page, size)

    @GetMapping("/api/knowledge/documents/{documentId}")
    fun document(@PathVariable documentId: String): KnowledgeDocumentDetails =
        knowledgeService.documentDetails(documentId)

    @DeleteMapping("/api/knowledge/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDocument(@PathVariable documentId: String) = knowledgeService.deleteDocument(documentId)

    @PostMapping("/api/knowledge/search")
    fun search(@RequestBody request: KnowledgeSearchRequest): List<KnowledgeMatch> =
        knowledgeService.search(request)
}
