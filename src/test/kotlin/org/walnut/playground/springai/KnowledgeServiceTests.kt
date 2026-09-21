package org.walnut.playground.springai

import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KnowledgeServiceTests {
    private val embeddingModel = mock(EmbeddingModel::class.java)
    private val vectorStore = mock(VectorStore::class.java)
    private val service = KnowledgeService(embeddingModel, vectorStore)

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
            stored = invocation.getArgument(0)
            null
        }.`when`(vectorStore).add(anyList())
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
            stored = invocation.getArgument(0)
            null
        }.`when`(vectorStore).add(anyList())
        assertEquals(1, service.ingest(KnowledgeDocumentRequest("Hi")).chunksIndexed)
        assertEquals("Hi", stored.single().text)
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
        )
        invalidRequests.forEach { request ->
            val error = assertFailsWith<ResponseStatusException> { request() }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }
        verifyNoInteractions(embeddingModel, vectorStore)
    }
}
