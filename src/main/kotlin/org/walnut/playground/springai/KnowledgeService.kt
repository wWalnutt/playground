package org.walnut.playground.springai

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
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
    private val vectorStore: VectorStore,
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

    fun ingest(request: KnowledgeDocumentRequest): KnowledgeDocumentResponse {
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
        vectorStore.add(chunks)
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
        return ingest(KnowledgeDocumentRequest(text, filename))
    }

    fun search(request: KnowledgeSearchRequest): List<KnowledgeMatch> {
        validateText(request.query, 2000)
        if (request.topK !in 1..20) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "topK must be between 1 and 20")
        }
        return vectorStore.similaritySearch(
            SearchRequest.builder().query(request.query).topK(request.topK).build(),
        ).map { document ->
            KnowledgeMatch(document.id, document.text, document.metadata, document.score)
        }
    }

    private fun validateText(text: String, maxLength: Int) {
        if (text.isBlank() || text.length > maxLength) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text must contain 1 to $maxLength characters")
        }
    }
}
