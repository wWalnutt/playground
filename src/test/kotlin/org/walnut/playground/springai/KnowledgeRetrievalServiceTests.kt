package org.walnut.playground.springai

import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KnowledgeRetrievalServiceTests {
    private val vectorStore = mock(VectorStore::class.java)
    private val service = KnowledgeRetrievalService(vectorStore, 0.6)

    @Test
    fun filtersOneBaselinePoolPreservingOrderAndInclusiveBoundary() {
        val pool = listOf(
            document("below", "rejected text", 0.59),
            document("equal", "equal text", 0.6, mapOf("source" to "policy")),
            document("blank", " \n\t", 0.9),
            document("null-text", null, 0.9),
            document("above", "above text", 0.8, mapOf("source" to 42)),
            document("negative", "cosine can be negative", -0.1),
        )
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenReturn(pool)
        val result = service.retrieve(KnowledgeSearchRequest("query", 6))
        assertEquals(listOf("equal", "above"), result.matches.map { it.id })
        assertEquals(listOf("equal text", "above text"), result.matches.map { it.text })
        assertEquals(6, result.diagnostics.topK)
        assertEquals(0.6, result.diagnostics.similarityThreshold)
        assertEquals(6, result.diagnostics.candidateCount)
        assertEquals(2, result.diagnostics.acceptedCount)
        assertEquals(4, result.diagnostics.rejectedCount)
        assertTrue(result.diagnostics.elapsedMs >= 0)
        assertEquals(
            listOf(RetrievalReason.BELOW_THRESHOLD, RetrievalReason.ACCEPTED, RetrievalReason.EMPTY_TEXT,
                RetrievalReason.EMPTY_TEXT, RetrievalReason.ACCEPTED, RetrievalReason.BELOW_THRESHOLD),
            result.diagnostics.candidates.map { it.reason },
        )
        assertEquals(listOf(false, true, false, false, true, false), result.diagnostics.candidates.map { it.accepted })
        assertEquals("policy", result.diagnostics.candidates[1].source)
        assertNull(result.diagnostics.candidates[4].source)
        assertEquals(-0.1, result.diagnostics.candidates.last().score)
        val captor = ArgumentCaptor.forClass(SearchRequest::class.java)
        verify(vectorStore).similaritySearch(captor.capture())
        assertEquals("query", captor.value.query)
        assertEquals(6, captor.value.topK)
        assertEquals(0.0, captor.value.similarityThreshold)
    }

    @Test
    fun nullUsesConfigurationAndExplicitZeroRestoresBaseline() {
        val pool = listOf(document("zero", "baseline", 0.0), document("one", "perfect", 1.0))
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenReturn(pool)
        assertEquals(listOf("one"), service.retrieve(KnowledgeSearchRequest("query")).matches.map { it.id })
        assertEquals(listOf("one"), service.retrieve(KnowledgeSearchRequest("query", 3, null)).matches.map { it.id })
        val baseline = service.retrieve(KnowledgeSearchRequest("query", 3, 0.0))
        assertEquals(0.0, baseline.diagnostics.similarityThreshold)
        assertEquals(listOf("zero", "one"), baseline.matches.map { it.id })
        assertEquals(listOf("one"), service.retrieve(KnowledgeSearchRequest("query", 3, 1.0)).matches.map { it.id })
    }

    @Test
    fun rejectsInvalidParametersBeforeDependencies() {
        val requests = listOf(
            KnowledgeSearchRequest(""), KnowledgeSearchRequest(" \t"),
            KnowledgeSearchRequest("x".repeat(2001)),
            KnowledgeSearchRequest("query", 0), KnowledgeSearchRequest("query", 21),
        ) + listOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .map { KnowledgeSearchRequest("query", 3, it) }
        requests.forEach {
            assertEquals(HttpStatus.BAD_REQUEST, assertFailsWith<ResponseStatusException> { service.retrieve(it) }.statusCode)
        }
        verifyNoInteractions(vectorStore)
    }

    @Test
    fun rejectsInvalidScoresEvenOnBlankCandidates() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { score ->
            val invalid = document("invalid", "", score)
            `when`(vectorStore.similaritySearch(any(SearchRequest::class.java)))
                .thenReturn(listOf(invalid))
            assertEquals(HttpStatus.BAD_GATEWAY, assertFailsWith<ResponseStatusException> {
                service.retrieve(KnowledgeSearchRequest("query"))
            }.statusCode)
        }
    }

    @Test
    fun emptyPoolHasZeroCountsAndUpstreamFailuresPropagate() {
        val diagnostics = service.retrieve(KnowledgeSearchRequest("query")).diagnostics
        assertEquals(0, diagnostics.candidateCount)
        assertEquals(0, diagnostics.acceptedCount)
        assertEquals(0, diagnostics.rejectedCount)
        assertTrue(diagnostics.candidates.isEmpty())
        val failure = IllegalStateException("Embedding or vector fetch failed")
        `when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenThrow(failure)
        assertSame(failure, assertFailsWith<IllegalStateException> { service.retrieve(KnowledgeSearchRequest("query")) })
    }

    @Test
    fun requiresValidConfigurationAtStartup() {
        val runner = ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=vectors")
            .withBean(VectorStore::class.java, { vectorStore })
            .withUserConfiguration(KnowledgeRetrievalService::class.java)
        runner.run { context -> assertNotNull(context.startupFailure) }
        listOf("0", "0.46", "0.7", "1").forEach { value ->
            val configured = runner.withPropertyValues("app.retrieval.similarity-threshold=$value")
            configured.run { context ->
                assertNull(context.startupFailure)
                val result = context.getBean(KnowledgeRetrievalService::class.java).retrieve(KnowledgeSearchRequest("query"))
                assertEquals(value.toDouble(), result.diagnostics.similarityThreshold)
            }
        }
        listOf("-0.1", "1.1", "NaN", "Infinity", "-Infinity", "invalid").forEach { value ->
            runner.withPropertyValues("app.retrieval.similarity-threshold=$value").run { context ->
                assertNotNull(context.startupFailure)
            }
        }
    }

    private fun document(id: String, text: String?, score: Double?, metadata: Map<String, Any> = emptyMap()): Document =
        mock(Document::class.java).also {
            `when`(it.id).thenReturn(id)
            `when`(it.text).thenReturn(text)
            `when`(it.score).thenReturn(score)
            `when`(it.metadata).thenReturn(metadata)
        }
}
