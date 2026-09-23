package org.walnut.playground.springai

import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

enum class RetrievalReason { ACCEPTED, BELOW_THRESHOLD, EMPTY_TEXT }

data class RetrievalCandidate(
    val chunkId: String,
    val source: String?,
    val score: Double,
    val accepted: Boolean,
    val reason: RetrievalReason,
)

data class RetrievalDiagnostics(
    val topK: Int,
    val similarityThreshold: Double,
    val candidateCount: Int,
    val acceptedCount: Int,
    val rejectedCount: Int,
    val elapsedMs: Long,
    val candidates: List<RetrievalCandidate>,
)

data class KnowledgeSearchResponse(
    val matches: List<KnowledgeMatch>,
    val diagnostics: RetrievalDiagnostics,
)

@Service
@Profile("vectors")
class KnowledgeRetrievalService(
    private val vectorStore: VectorStore,
    @Value("\${app.retrieval.similarity-threshold}") private val defaultSimilarityThreshold: Double,
) {
    init {
        require(validThreshold(defaultSimilarityThreshold)) {
            "app.retrieval.similarity-threshold must be finite and between 0 and 1"
        }
    }

    fun retrieve(request: KnowledgeSearchRequest): KnowledgeSearchResponse {
        validate(request)
        val threshold = request.similarityThreshold ?: defaultSimilarityThreshold
        val started = System.nanoTime()
        // Fetch the baseline topK pool once; thresholding never expands or reranks it.
        val documents = vectorStore.similaritySearch(
            SearchRequest.builder().query(request.query).topK(request.topK).similarityThreshold(0.0).build(),
        )
        val matches = mutableListOf<KnowledgeMatch>()
        val candidates = documents.map { document ->
            val score = document.score
            if (score == null || !score.isFinite()) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Vector store returned a missing or non-finite score")
            }
            val reason = when {
                document.text.isNullOrBlank() -> RetrievalReason.EMPTY_TEXT
                score < threshold -> RetrievalReason.BELOW_THRESHOLD
                else -> RetrievalReason.ACCEPTED
            }
            val accepted = reason == RetrievalReason.ACCEPTED
            if (accepted) {
                matches.add(KnowledgeMatch(document.id, document.text, document.metadata, score))
            }
            RetrievalCandidate(document.id, document.metadata["source"] as? String, score, accepted, reason)
        }
        return KnowledgeSearchResponse(
            matches,
            RetrievalDiagnostics(
                request.topK, threshold, candidates.size, matches.size, candidates.size - matches.size,
                (System.nanoTime() - started) / 1_000_000, candidates,
            ),
        )
    }

    fun validate(request: KnowledgeSearchRequest) {
        if (request.query.isBlank() || request.query.length > 2000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "query must contain 1 to 2000 characters")
        }
        if (request.topK !in 1..20) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "topK must be between 1 and 20")
        }
        val threshold = request.similarityThreshold ?: defaultSimilarityThreshold
        if (!validThreshold(threshold)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "similarityThreshold must be finite and between 0 and 1")
        }
    }

    private fun validThreshold(value: Double): Boolean = value.isFinite() && value in 0.0..1.0
}
