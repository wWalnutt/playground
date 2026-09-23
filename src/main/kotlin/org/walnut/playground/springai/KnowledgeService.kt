package org.walnut.playground.springai

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.multipart.MultipartFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Locale
import java.util.UUID

@Service
@Profile("vectors")
class KnowledgeService(
    private val embeddingModel: EmbeddingModel,
    private val retrievalService: KnowledgeRetrievalService,
    private val documents: KnowledgeDocumentRepository,
) {
    private val splitter = TokenTextSplitter.builder()
        .withChunkSize(500)
        .withMinChunkSizeChars(100)
        .withMinChunkLengthToEmbed(0)
        .withMaxNumChunks(1000)
        .build()

    fun embed(text: String): EmbeddingResponse {
        validateText(text, 2000)
        val vector = embeddingModel.embed(text)
        check(vector.size == 1024) { "bge-m3 must return 1024-dimensional vectors" }
        return EmbeddingResponse("bge-m3", vector.size, vector.toList())
    }

    fun ingest(request: KnowledgeDocumentRequest): KnowledgeDocumentResponse =
        ingest(request, KnowledgeUploadType.TEXT)

    private fun ingest(request: KnowledgeDocumentRequest, uploadType: KnowledgeUploadType): KnowledgeDocumentResponse {
        validateText(request.text, 20000)
        if (request.source.isBlank() || request.source.length > 200) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "source must contain 1 to 200 characters")
        }
        val documentId = UUID.randomUUID().toString()
        val chunks = splitter.split(
            Document(request.text, mapOf("source" to request.source, "documentId" to documentId, "model" to "bge-m3")),
        )
        if (chunks.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text contains no indexable content")
        }
        chunks.forEachIndexed { index, chunk -> chunk.metadata["chunkIndex"] = index }
        documents.index(UUID.fromString(documentId), request.source, uploadType, chunks)
        return KnowledgeDocumentResponse(documentId, chunks.size)
    }

    fun ingestFile(file: MultipartFile): KnowledgeDocumentResponse {
        val filename = file.originalFilename.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        if (filename.isBlank() || filename.length > 200) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "filename must contain 1 to 200 characters")
        }
        if (filename.substringAfterLast('.', "").lowercase(Locale.ROOT) !in setOf("txt", "md", "markdown")) {
            throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only TXT and Markdown files are supported")
        }
        if (file.size > 65536) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File must not exceed 64 KiB")
        }
        val bytes = file.inputStream.use { it.readNBytes(65537) }
        if (bytes.size > 65536) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File must not exceed 64 KiB")
        }
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (exception: CharacterCodingException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be UTF-8 encoded", exception)
        }
        if (text.any { it.isISOControl() && it !in "\n\r\t" }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must contain plain text, not binary data")
        }
        return ingest(KnowledgeDocumentRequest(text, filename), KnowledgeUploadType.FILE)
    }

    fun listDocuments(page: Int, size: Int): KnowledgeDocumentPage {
        if (page < 0 || size !in 1..100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be non-negative and size must be between 1 and 100")
        }
        return documents.list(page, size)
    }

    fun documentDetails(documentId: String): KnowledgeDocumentDetails = documents.details(parseDocumentId(documentId))

    fun deleteDocument(documentId: String) = documents.delete(parseDocumentId(documentId))

    private fun parseDocumentId(value: String): UUID {
        if (!value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "documentId must be a UUID")
        }
        return UUID.fromString(value)
    }

    fun search(request: KnowledgeSearchRequest): List<KnowledgeMatch> = searchWithDiagnostics(request).matches

    fun searchWithDiagnostics(request: KnowledgeSearchRequest): KnowledgeSearchResponse =
        retrievalService.retrieve(request)

    fun validateSearch(request: KnowledgeSearchRequest) = retrievalService.validate(request)

    private fun validateText(text: String, maxLength: Int) {
        if (text.isBlank() || text.length > maxLength) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text must contain 1 to $maxLength characters")
        }
    }
}
