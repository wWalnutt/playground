package org.walnut.playground.springai

import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KnowledgeServiceTests {
    private val embeddingModel = mock(EmbeddingModel::class.java)
    private val vectorStore = mock(VectorStore::class.java)
    private val documents = mock(KnowledgeDocumentRepository::class.java)
    private val service = KnowledgeService(embeddingModel, vectorStore, documents)

    @Test
    fun embedsUsingBgeDimensions() {
        `when`(embeddingModel.embed("hello")).thenReturn(FloatArray(1024) { 0.5f })
        val result = service.embed("hello")
        assertEquals("bge-m3", result.model)
        assertEquals(1024, result.dimensions)
        assertEquals(1024, result.vector.size)
        verifyNoInteractions(vectorStore)
    }

    @Test
    fun rejectsUnexpectedEmbeddingDimensions() {
        `when`(embeddingModel.embed("hello")).thenReturn(FloatArray(768))
        assertFailsWith<IllegalStateException> { service.embed("hello") }
    }

    @Test
    fun splitsTextAndPreservesSourceMetadata() {
        var stored = emptyList<Document>()
        doAnswer { invocation ->
            stored = invocation.getArgument(3)
            null
        }.`when`(documents).index(any(UUID::class.java) ?: UUID(0, 0), anyString(),
            any(KnowledgeUploadType::class.java) ?: KnowledgeUploadType.TEXT, anyList())
        val result = service.ingest(
            KnowledgeDocumentRequest("Travel expenses must be submitted within 30 days. ".repeat(200), "policy"),
        )
        assertTrue(stored.size > 1)
        assertEquals(stored.size, result.chunksIndexed)
        stored.forEachIndexed { index, document ->
            assertEquals("policy", document.metadata["source"])
            assertEquals(result.documentId, document.metadata["documentId"])
            assertEquals(index, document.metadata["chunkIndex"])
            assertEquals("bge-m3", document.metadata["model"])
            assertTrue(!document.text.isNullOrBlank())
        }
    }

    @Test
    fun keepsShortText() {
        var stored = emptyList<Document>()
        doAnswer { invocation ->
            stored = invocation.getArgument(3)
            assertEquals(KnowledgeUploadType.TEXT, invocation.getArgument(2))
            null
        }.`when`(documents).index(any(UUID::class.java) ?: UUID(0, 0), anyString(),
            any(KnowledgeUploadType::class.java) ?: KnowledgeUploadType.TEXT, anyList())
        assertEquals(1, service.ingest(KnowledgeDocumentRequest("Hi")).chunksIndexed)
        assertEquals("Hi", stored.single().text)
    }

    @Test
    fun acceptsUtf8FilesAndPreservesCleanFilename() {
        var stored = emptyList<Document>()
        doAnswer { invocation ->
            stored = invocation.getArgument(3)
            assertEquals(KnowledgeUploadType.FILE, invocation.getArgument(2))
            null
        }.`when`(documents).index(any(UUID::class.java) ?: UUID(0, 0), anyString(),
            any(KnowledgeUploadType::class.java) ?: KnowledgeUploadType.TEXT, anyList())
        val text = "差旅报销须在30天内提交。"
        listOf("policy.txt", "policy.MD", "policy.markdown").forEach { filename ->
            val file = MockMultipartFile("file", "C:\\fakepath\\$filename", "application/octet-stream",
                ("\uFEFF" + text).toByteArray(Charsets.UTF_8))
            val result = service.ingestFile(file)
            assertEquals(1, result.chunksIndexed)
            assertEquals(text, stored.single().text)
            assertEquals(filename, stored.single().metadata["source"])
            assertEquals(result.documentId, stored.single().metadata["documentId"])
        }
    }

    @Test
    fun rejectsUnsupportedEmptyOversizedBinaryAndInvalidUtf8Files() {
        val cases = listOf(
            Triple("policy.pdf", "text".toByteArray(), HttpStatus.UNSUPPORTED_MEDIA_TYPE),
            Triple("policy.txt", byteArrayOf(), HttpStatus.BAD_REQUEST),
            Triple("policy.txt", "\uFEFF \n\t".toByteArray(), HttpStatus.BAD_REQUEST),
            Triple("policy.txt", byteArrayOf(0xc3.toByte(), 0x28), HttpStatus.BAD_REQUEST),
            Triple("policy.txt", byteArrayOf(0, 1, 2), HttpStatus.BAD_REQUEST),
            Triple("policy.txt", ByteArray(65537) { 65 }, HttpStatus.PAYLOAD_TOO_LARGE),
            Triple("policy.txt", "a".repeat(20001).toByteArray(), HttpStatus.BAD_REQUEST),
            Triple("", "text".toByteArray(), HttpStatus.BAD_REQUEST),
            Triple("a".repeat(200) + ".txt", "text".toByteArray(), HttpStatus.BAD_REQUEST),
        )
        cases.forEach { (name, bytes, expectedStatus) ->
            val error = assertFailsWith<ResponseStatusException> {
                service.ingestFile(MockMultipartFile("file", name, "text/plain", bytes))
            }
            assertEquals(expectedStatus, error.statusCode)
        }
        verifyNoInteractions(embeddingModel, vectorStore, documents)
    }

    @Test
    fun searchesWithRequestedQueryAndTopK() {
        // Mockito's empty collection default also represents a valid search with no matches.
        assertTrue(service.search(KnowledgeSearchRequest("travel", 4)).isEmpty())
        val captor = ArgumentCaptor.forClass(SearchRequest::class.java)
        verify(vectorStore).similaritySearch(captor.capture())
        assertEquals("travel", captor.value.query)
        assertEquals(4, captor.value.topK)
    }

    @Test
    fun rejectsInvalidInputsBeforeCallingDependencies() {
        val invalidRequests = listOf<() -> Unit>(
            { service.embed(" ") },
            { service.embed("x".repeat(2001)) },
            { service.ingest(KnowledgeDocumentRequest("")) },
            { service.ingest(KnowledgeDocumentRequest("x".repeat(20001))) },
            { service.ingest(KnowledgeDocumentRequest("text", "")) },
            { service.search(KnowledgeSearchRequest("")) },
            { service.search(KnowledgeSearchRequest("question", 0)) },
            { service.search(KnowledgeSearchRequest("question", 21)) },
            { service.listDocuments(-1, 10) },
            { service.listDocuments(0, 0) },
            { service.listDocuments(0, 101) },
            { service.documentDetails("1-1-1-1-1") },
            { service.deleteDocument("not-a-uuid") },
        )
        invalidRequests.forEach { request ->
            val error = assertFailsWith<ResponseStatusException> { request() }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }
        verifyNoInteractions(embeddingModel, vectorStore, documents)
    }
}
