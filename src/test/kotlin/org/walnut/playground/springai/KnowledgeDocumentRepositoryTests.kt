package org.walnut.playground.springai

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.BatchingStrategy
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingOptions
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt in with KNOWLEDGE_TEST_POSTGRES=true and KNOWLEDGE_TEST_JDBC_URL/USER/PASSWORD.
// All writes, including PgVectorStore writes, are confined to a fresh random schema.
@EnabledIfEnvironmentVariable(named = "KNOWLEDGE_TEST_POSTGRES", matches = "true")
class KnowledgeDocumentRepositoryTests {
    private val schema = "knowledge_test_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: KnowledgeDocumentRepository
    private lateinit var store: PgVectorStore
    private lateinit var embedding: EmbeddingModel
    private val table get() = "\"$schema\".chunks"
    private val management get() = "\"$schema\".chunks_management"

    @BeforeEach
    fun setUp() {
        val dataSource = DriverManagerDataSource(
            requireNotNull(System.getenv("KNOWLEDGE_TEST_JDBC_URL")),
            requireNotNull(System.getenv("KNOWLEDGE_TEST_USER")),
            requireNotNull(System.getenv("KNOWLEDGE_TEST_PASSWORD")),
        )
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE SCHEMA \"$schema\"")
        jdbc.execute("CREATE TABLE $table (id uuid PRIMARY KEY, content text, metadata jsonb, embedding vector(3))")
        embedding = mock(EmbeddingModel::class.java)
        embeddings { chunks -> chunks.map { floatArrayOf(0.1f, 0.2f, 0.3f) } }
        store = PgVectorStore.builder(jdbc, embedding)
            .schemaName(schema).vectorTableName("chunks").dimensions(3)
            .initializeSchema(false).vectorTableValidationsEnabled(false)
            .maxDocumentBatchSize(1).build()
        repository = KnowledgeDocumentRepository(jdbc, store, schema, "chunks")
        repository.initialize()
    }

    @AfterEach
    fun tearDown() {
        if (::jdbc.isInitialized) jdbc.execute("DROP SCHEMA IF EXISTS \"$schema\" CASCADE")
    }

    @Test
    fun indexesListsPaginatesShowsChunksAndDeletesOnlySelectedDocument() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        repository.index(first, "first.md", KnowledgeUploadType.FILE, chunks(first, 2))
        repository.index(second, "inline", KnowledgeUploadType.TEXT, chunks(second, 1))
        val page = repository.list(0, 1)
        assertEquals(2, page.totalPages)
        assertEquals(2L, page.totalElements)
        assertEquals(1, page.items.size)
        assertEquals(second.toString(), page.items.single().documentId)
        assertEquals(first.toString(), repository.list(1, 1).items.single().documentId)
        assertTrue(repository.list(Int.MAX_VALUE, 100).items.isEmpty())
        val details = repository.details(first)
        assertEquals(KnowledgeDocumentStatus.READY, details.document.status)
        assertEquals(KnowledgeUploadType.FILE, details.document.uploadType)
        assertNotNull(details.document.uploadedAt)
        assertEquals(2, details.document.chunksIndexed)
        assertEquals(listOf(0, 1), details.chunks.map { it.chunkIndex })
        assertEquals(listOf("chunk 0", "chunk 1"), details.chunks.map { it.text })
        repository.delete(first)
        assertEquals(1, repository.details(second).document.chunksIndexed)
        assertEquals(1L, repository.list(0, 10).totalElements)
        assertEquals(1, vectorCount())
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> { repository.details(first) }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> { repository.delete(first) }.statusCode)
    }

    @Test
    fun rollsBackEarlierPgVectorBatchesAndPersistsVisibleFailure() {
        val id = UUID.randomUUID()
        embeddings { listOf(floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(0.1f, 0.2f)) }
        assertFailsWith<DataAccessException> {
            repository.index(id, "broken", KnowledgeUploadType.TEXT, chunks(id, 2))
        }
        val details = repository.details(id)
        assertEquals(KnowledgeDocumentStatus.FAILED, details.document.status)
        assertEquals(0, details.document.chunksIndexed)
        assertTrue(details.chunks.isEmpty())
        assertEquals(0, vectorCount())
        repository.delete(id)
        assertEquals(0L, repository.list(0, 10).totalElements)
    }

    @Test
    fun activeUploadIsVisibleCannotBeDeletedOrRecoveredByAnotherInstance() {
        val id = UUID.randomUUID()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        embeddings { input ->
            started.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Upload test timed out" }
            input.map { floatArrayOf(0.1f, 0.2f, 0.3f) }
        }
        Executors.newSingleThreadExecutor().use { executor ->
            val result = executor.submit {
                repository.index(id, "active", KnowledgeUploadType.TEXT, chunks(id, 2))
            }
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS))
                val otherInstance = KnowledgeDocumentRepository(jdbc, store, schema, "chunks")
                otherInstance.initialize()
                val summary = otherInstance.list(0, 10).items.single()
                assertEquals(KnowledgeDocumentStatus.PROCESSING, summary.status)
                assertEquals(0, summary.chunksIndexed)
                assertTrue(otherInstance.details(id).chunks.isEmpty())
                assertEquals(
                    HttpStatus.CONFLICT,
                    assertFailsWith<ResponseStatusException> { otherInstance.delete(id) }.statusCode,
                )
            } finally {
                release.countDown()
            }
            result.get(10, TimeUnit.SECONDS)
        }
        assertEquals(KnowledgeDocumentStatus.READY, repository.details(id).document.status)
        repository.delete(id)
        assertEquals(0, vectorCount())
    }

    @Test
    fun backfillsLegacyWithoutClaimingCompletenessAndIsIdempotent() {
        val id = UUID.randomUUID()
        insertVector(id, 1)
        insertVector(id, 0)
        jdbc.execute("ALTER TABLE $table ALTER COLUMN metadata TYPE json USING metadata::json")
        repository.initialize()
        repository.initialize()
        val page = repository.list(0, 10)
        val legacy = page.items.single()
        assertEquals(id.toString(), legacy.documentId)
        assertEquals(KnowledgeUploadType.LEGACY, legacy.uploadType)
        assertEquals(KnowledgeDocumentStatus.LEGACY, legacy.status)
        assertNull(legacy.uploadedAt)
        assertEquals(2, legacy.chunksIndexed)
        assertEquals(listOf(0, 1), repository.details(id).chunks.map { it.chunkIndex })
        repository.delete(id)
        repository.initialize()
        assertEquals(0L, repository.list(0, 10).totalElements)
        assertEquals(0, vectorCount())
    }

    @Test
    fun recoversInterruptedUploadsAndReportsActualChunksEvenForFailedRecords() {
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO $management VALUES (?, 'interrupted', 'TEXT', CURRENT_TIMESTAMP, 'PROCESSING')""", id,
        )
        insertVector(id, 0)
        repository.initialize()
        val details = repository.details(id)
        assertEquals(KnowledgeDocumentStatus.FAILED, details.document.status)
        assertEquals(1, details.document.chunksIndexed)
        assertEquals(1, details.chunks.size)
        repository.delete(id)
        assertEquals(0, vectorCount())
    }

    @Test
    fun modelFailureIsRethrownAndFailedUploadSurvivesReinitialization() {
        val id = UUID.randomUUID()
        val failure = IllegalStateException("Embedding provider unavailable")
        embeddings { throw failure }
        val thrown = assertFailsWith<IllegalStateException> {
            repository.index(id, "failed.txt", KnowledgeUploadType.FILE, chunks(id, 1))
        }
        assertTrue(thrown === failure)
        repository.initialize()
        val failed = repository.list(0, 10).items.single()
        assertEquals(KnowledgeDocumentStatus.FAILED, failed.status)
        assertEquals(KnowledgeUploadType.FILE, failed.uploadType)
        assertEquals("failed.txt", failed.source)
        assertEquals(0, failed.chunksIndexed)
        assertNotNull(failed.uploadedAt)
    }

    @Test
    fun concurrentUploadsDoNotDeadlockASmallConnectionPoolDuringRegistration() {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = System.getenv("KNOWLEDGE_TEST_JDBC_URL")
            username = System.getenv("KNOWLEDGE_TEST_USER")
            password = System.getenv("KNOWLEDGE_TEST_PASSWORD")
            maximumPoolSize = 2
            connectionTimeout = 3000
        }).use { dataSource ->
            val pooledJdbc = JdbcTemplate(dataSource)
            val pooledStore = PgVectorStore.builder(pooledJdbc, embedding)
                .schemaName(schema).vectorTableName("chunks").dimensions(3)
                .initializeSchema(false).vectorTableValidationsEnabled(false).build()
            val pooledRepository = KnowledgeDocumentRepository(pooledJdbc, pooledStore, schema, "chunks")
            val start = CountDownLatch(1)
            Executors.newFixedThreadPool(8).use { executor ->
                val uploads = List(16) {
                    executor.submit {
                        check(start.await(10, TimeUnit.SECONDS))
                        val id = UUID.randomUUID()
                        pooledRepository.index(id, "concurrent", KnowledgeUploadType.TEXT, chunks(id, 1))
                    }
                }
                start.countDown()
                uploads.forEach { it.get(10, TimeUnit.SECONDS) }
            }
        }
        assertEquals(16, vectorCount())
        assertTrue(repository.list(0, 100).items.all { it.status == KnowledgeDocumentStatus.READY })
    }

    @Test
    fun rollsBackVectorDeletionWhenManagementDeletionFails() {
        val id = UUID.randomUUID()
        repository.index(id, "keep", KnowledgeUploadType.TEXT, chunks(id, 2))
        jdbc.execute(
            """CREATE FUNCTION "$schema".reject_delete() RETURNS trigger LANGUAGE plpgsql AS
               'BEGIN RAISE EXCEPTION ''test delete failure''; END'""",
        )
        jdbc.execute(
            """CREATE TRIGGER reject_delete BEFORE DELETE ON $management
               FOR EACH ROW EXECUTE FUNCTION "$schema".reject_delete()""",
        )
        assertFailsWith<DataAccessException> { repository.delete(id) }
        assertEquals(2, vectorCount())
        assertEquals(KnowledgeDocumentStatus.READY, repository.details(id).document.status)
    }

    @Test
    fun rejectsUnsafeConfiguredIdentifiers() {
        assertFailsWith<IllegalArgumentException> {
            KnowledgeDocumentRepository(jdbc, store, "public; DROP SCHEMA public", "chunks")
        }
        assertFailsWith<IllegalArgumentException> {
            KnowledgeDocumentRepository(jdbc, store, schema, "x".repeat(64))
        }
    }

    private fun chunks(id: UUID, count: Int) = List(count) { index ->
        Document("chunk $index", mapOf("documentId" to id.toString(), "source" to "test", "chunkIndex" to index))
    }

    private fun insertVector(id: UUID, index: Int) {
        jdbc.update(
            """INSERT INTO $table (id, content, metadata) VALUES (?, ?, ?::jsonb)""",
            UUID.randomUUID(), "legacy $index",
            """{"documentId":"$id","source":"legacy","chunkIndex":$index}""",
        )
    }

    private fun vectorCount(): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun embeddings(answer: (List<Document>) -> List<FloatArray>) {
        doAnswer { invocation -> answer(invocation.getArgument(0)) }
            .`when`(embedding).embed(anyList(), any(EmbeddingOptions::class.java), any(BatchingStrategy::class.java))
    }
}
