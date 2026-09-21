package org.walnut.playground.springai

import jakarta.annotation.PostConstruct
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

enum class KnowledgeUploadType { TEXT, FILE, LEGACY }
enum class KnowledgeDocumentStatus { PROCESSING, READY, FAILED, LEGACY }

data class KnowledgeDocumentSummary(
    val documentId: String,
    val source: String,
    val uploadType: KnowledgeUploadType,
    val uploadedAt: Instant?,
    val chunksIndexed: Int,
    val status: KnowledgeDocumentStatus,
)

data class KnowledgeDocumentPage(
    val items: List<KnowledgeDocumentSummary>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class KnowledgeChunk(val id: String, val chunkIndex: Int, val text: String)
data class KnowledgeDocumentDetails(val document: KnowledgeDocumentSummary, val chunks: List<KnowledgeChunk>)

@Repository
@Profile("vectors")
class KnowledgeDocumentRepository(
    private val jdbc: JdbcTemplate,
    private val vectorStore: VectorStore,
    @Value("\${spring.ai.vectorstore.pgvector.schema-name:public}") schemaName: String,
    @Value("\${spring.ai.vectorstore.pgvector.table-name:vector_store}") tableName: String,
) {
    private val vectors = "${identifier(schemaName)}.${identifier(tableName)}"
    private val documents = "${identifier(schemaName)}.${identifier(tableName + "_management")}"
    private val transactionManager = DataSourceTransactionManager(requireNotNull(jdbc.dataSource))
    private val registration = ReentrantLock()
    private val write = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val read = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }
    private val summarySql = """
        SELECT d.document_id, d.source, d.upload_type, d.uploaded_at, d.status,
            (SELECT count(*) FROM $vectors v
             WHERE lower(v.metadata->>'documentId') = d.document_id::text) AS chunks_indexed
        FROM $documents d
    """.trimIndent()

    @PostConstruct
    fun initialize() {
        // VectorStore is a constructor dependency: its schema initialization completes first.
        write.executeWithoutResult {
            lock("initialize")
            jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS $documents (
                    document_id uuid PRIMARY KEY,
                    source text NOT NULL,
                    upload_type varchar(10) NOT NULL CHECK (upload_type IN ('TEXT', 'FILE', 'LEGACY')),
                    uploaded_at timestamptz,
                    status varchar(10) NOT NULL CHECK (status IN ('PROCESSING', 'READY', 'FAILED', 'LEGACY'))
                )
                """.trimIndent(),
            )
            jdbc.update(
                """
                INSERT INTO $documents (document_id, source, upload_type, uploaded_at, status)
                SELECT (metadata->>'documentId')::uuid, COALESCE(min(metadata->>'source'), 'unknown'),
                    'LEGACY', NULL, 'LEGACY'
                FROM $vectors
                WHERE metadata->>'documentId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                GROUP BY (metadata->>'documentId')::uuid
                ON CONFLICT (document_id) DO NOTHING
                """.trimIndent(),
            )
        }
        recoverInterrupted()
    }

    fun index(documentId: UUID, source: String, uploadType: KnowledgeUploadType, chunks: List<Document>) {
        // Only registration needs two connections. Serialize this short phase so concurrent
        // uploads cannot exhaust the pool with outer transactions all waiting for an inner one.
        registration.lock()
        try {
            write.executeWithoutResult {
                // The lock precedes the separately committed PROCESSING row, eliminating any
                // window in which another instance could mistake a live upload for an orphan.
                lock(documentId.toString())
                write.executeWithoutResult {
                    jdbc.update(
                        """INSERT INTO $documents (document_id, source, upload_type, uploaded_at, status)
                           VALUES (?, ?, ?, CURRENT_TIMESTAMP, 'PROCESSING')""",
                        documentId, source, uploadType.name,
                    )
                }
                registration.unlock()
                // PgVectorStore uses the same DataSource; every batch and READY commit together.
                vectorStore.add(chunks)
                check(jdbc.update(
                    "UPDATE $documents SET status = 'READY' WHERE document_id = ? AND status = 'PROCESSING'",
                    documentId,
                ) == 1) { "Upload record disappeared while indexing" }
            }
        } catch (failure: RuntimeException) {
            // This is the ingestion transaction boundary. Preserve a visible failure after
            // rollback, and always propagate the original error (including recovery failures).
            try {
                write.executeWithoutResult {
                    jdbc.update(
                        "UPDATE $documents SET status = 'FAILED' WHERE document_id = ? AND status = 'PROCESSING'",
                        documentId,
                    )
                }
            } catch (recordFailure: RuntimeException) {
                failure.addSuppressed(recordFailure)
            }
            throw failure
        } finally {
            if (registration.isHeldByCurrentThread) registration.unlock()
        }
    }

    fun list(page: Int, size: Int): KnowledgeDocumentPage {
        recoverInterrupted()
        return read.execute {
            val total = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $documents", Long::class.java))
            val items = jdbc.query(
                "$summarySql ORDER BY d.uploaded_at DESC NULLS LAST, d.document_id LIMIT ? OFFSET ?",
                { rs, _ -> summary(rs) }, size, page.toLong() * size,
            )
            KnowledgeDocumentPage(items, page, size, total, ((total + size - 1) / size).toInt())
        }
    }

    fun details(documentId: UUID): KnowledgeDocumentDetails {
        recoverInterrupted()
        return read.execute {
            val document = find(documentId)
            val chunks = jdbc.query(
                """
                SELECT id, content, CASE
                    WHEN metadata->>'chunkIndex' ~ '^[0-9]{1,9}$' THEN (metadata->>'chunkIndex')::int
                    ELSE 0 END AS chunk_index
                FROM $vectors WHERE lower(metadata->>'documentId') = ?
                ORDER BY chunk_index, id
                """.trimIndent(),
                { rs, _ -> KnowledgeChunk(rs.getString("id"), rs.getInt("chunk_index"), rs.getString("content").orEmpty()) },
                documentId.toString(),
            )
            KnowledgeDocumentDetails(document, chunks)
        }
    }

    fun delete(documentId: UUID) {
        write.executeWithoutResult {
            // Serialize deletion with startup backfill so its snapshot cannot resurrect a
            // just-deleted management row as a legacy document.
            jdbc.query(
                "SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))",
                { rs, _ -> rs.getObject(1) }, "$documents/initialize",
            )
            if (!tryLock(documentId.toString())) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Document is still processing")
            }
            val document = find(documentId)
            if (document.status == KnowledgeDocumentStatus.PROCESSING) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Document is still processing")
            }
            jdbc.update("DELETE FROM $vectors WHERE lower(metadata->>'documentId') = ?", documentId.toString())
            jdbc.update("DELETE FROM $documents WHERE document_id = ?", documentId)
        }
    }

    private fun recoverInterrupted() {
        write.executeWithoutResult {
            val processing = jdbc.query(
                "SELECT document_id FROM $documents WHERE status = 'PROCESSING'",
                { rs, _ -> rs.getObject("document_id", UUID::class.java) },
            )
            processing.forEach { id ->
                if (tryLock(id.toString())) {
                    // A crashed ingestion loses its transaction lock and its uncommitted vectors.
                    jdbc.update(
                        "UPDATE $documents SET status = 'FAILED' WHERE document_id = ? AND status = 'PROCESSING'",
                        id,
                    )
                }
            }
        }
    }

    private fun find(documentId: UUID): KnowledgeDocumentSummary =
        jdbc.query("$summarySql WHERE d.document_id = ?", { rs, _ -> summary(rs) }, documentId)
            .singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found")

    private fun summary(rs: ResultSet) = KnowledgeDocumentSummary(
        documentId = rs.getString("document_id"),
        source = rs.getString("source"),
        uploadType = KnowledgeUploadType.valueOf(rs.getString("upload_type")),
        uploadedAt = rs.getTimestamp("uploaded_at")?.toInstant(),
        chunksIndexed = rs.getInt("chunks_indexed"),
        status = KnowledgeDocumentStatus.valueOf(rs.getString("status")),
    )

    private fun lock(key: String) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", { rs, _ -> rs.getObject(1) }, "$documents/$key")
    }

    private fun tryLock(key: String): Boolean =
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean::class.java, "$documents/$key",
        ) == true

    companion object {
        private fun identifier(value: String): String {
            require(value.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]{0,62}"))) { "Invalid PostgreSQL identifier: $value" }
            return "\"$value\""
        }
    }
}
