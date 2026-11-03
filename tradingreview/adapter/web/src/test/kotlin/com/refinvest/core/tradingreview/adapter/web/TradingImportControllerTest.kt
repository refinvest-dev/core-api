package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionResult
import com.refinvest.core.tradingreview.port.importsession.TradingSourceArtifactResult
import com.refinvest.core.tradingreview.port.importsession.create.CreateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.importsession.create.CreateTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.importsession.delete.DeleteTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.importsession.delete.DeleteTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.importsession.deleteartifact.DeleteTradingSourceArtifactCommand
import com.refinvest.core.tradingreview.port.importsession.deleteartifact.DeleteTradingSourceArtifactUseCase
import com.refinvest.core.tradingreview.port.importsession.get.GetTradingImportSessionQuery
import com.refinvest.core.tradingreview.port.importsession.get.GetTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.importsession.list.ListTradingImportsQuery
import com.refinvest.core.tradingreview.port.importsession.list.ListTradingImportsUseCase
import com.refinvest.core.tradingreview.port.importsession.upload.UploadTradingImportArtifactCommand
import com.refinvest.core.tradingreview.port.importsession.upload.UploadTradingImportArtifactUseCase
import com.refinvest.core.tradingreview.port.normalization.retry.RetryTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.normalization.retry.RetryTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.normalization.validate.ValidateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.normalization.validate.ValidateTradingImportSessionUseCase

import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionView
import com.refinvest.core.tradingreview.port.importsession.upload.TradingUploadMetadata
import com.refinvest.core.tradingreview.port.importsession.upload.UploadArtifactResult
import com.refinvest.core.tradingreview.port.ledger.PublishedLedgerSummary
import com.refinvest.core.tradingreview.port.pagination.TradingPage

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportFailure
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper

class TradingImportControllerTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val mapper = JsonMapper.builder().build()
    private val session = TradingImportSession.restore(TradingImportSessionId(10), TradingBookId(20), MemberId(30),
        "UTC", "Asia/Seoul", TradingPositionMode.ONE_WAY, "TRADING_DATA_POLICY_V1", "TRADING_RETENTION_V1",
        "SOURCE_EVIDENCE_MASK_V1", "BOOK_LIFECYCLE", "TRADING_DELETION_V1",
        "{\"versionSetHash\":\"exact\"}", TradingImportStatus.RECEIVED, 0, false, now, now, 0)
    private var currentSession = session
    private var published: PublishedLedgerSummary? = null
    private val artifacts = mutableListOf<TradingSourceArtifact>()
    private val deletion = TradingDeletion(90, MemberId(30), "TRADING_IMPORT_SESSION", 10,
        "IMPORT_SESSION_SUBTREE", "REQUESTED", 1, 0, 0, now, now, now, now.plusSeconds(86400),
        importSessionCount = 1, artifactCount = 2)
    private val imports = object : CreateTradingImportSessionUseCase, GetTradingImportSessionUseCase, ListTradingImportsUseCase,
        UploadTradingImportArtifactUseCase, DeleteTradingImportSessionUseCase, DeleteTradingSourceArtifactUseCase {
        override fun execute(command: CreateTradingImportSessionCommand) = TradingImportSessionResult.fromDomain(session)
        override fun execute(query: GetTradingImportSessionQuery) = TradingImportSessionView.fromDomain(currentSession, artifacts.toList(), published, now)
        override fun execute(query: ListTradingImportsQuery) = TradingPage(listOf(execute(GetTradingImportSessionQuery(session.id))), query.page, query.size, 1)
        override fun execute(command: UploadTradingImportArtifactCommand): UploadArtifactResult {
            val id = command.id
            val source = command.source
            val metadata = command.metadata

            val bytes = source.readAllBytes()
            val value = metadata()
            assertEquals(10L, id.value)
            assertEquals(bytes.size.toLong(), value.byteSize)
            assertEquals(sha(bytes), value.sha256)
            val artifact = TradingSourceArtifact.restore(TradingSourceArtifactId(40 + artifacts.size.toLong()), id,
                value.role, value.filename, value.contentType, value.byteSize, value.sha256, value.dialectCandidate,
                "objects/00000000-0000-0000-0000-000000000040", byteArrayOf(1), "test-v1",
                TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(86400), null, null, 0)
            artifacts.add(artifact)
            return UploadArtifactResult(TradingSourceArtifactResult.fromDomain(artifact, now), true)
                }
        override fun execute(command: DeleteTradingImportSessionCommand) = deletion
        override fun execute(command: DeleteTradingSourceArtifactCommand) = deletion
    }
    private val normalization = object : ValidateTradingImportSessionUseCase, RetryTradingImportSessionUseCase {
        override fun execute(command: ValidateTradingImportSessionCommand) = TradingImportSessionResult.fromDomain(session)
        override fun execute(command: RetryTradingImportSessionCommand) = TradingImportSessionResult.fromDomain(session)
    }
    private val mvc = MockMvcBuilders.standaloneSetup(
        TradingBookImportController(imports, imports, imports, mapper),
        TradingImportSessionController(imports, normalization, normalization, imports, imports, mapper),
        TradingSourceArtifactController(imports),
    ).setControllerAdvice(TradingReviewExceptionHandler()).build()

    private fun restoreSession(status: TradingImportStatus, failure: TradingImportFailure? = null) =
        TradingImportSession.restore(session.id, session.bookId, session.owner, session.sourceTimezone,
            session.reviewTimezone, session.declaredPositionMode, session.dataPolicyVersion, session.retentionPolicy,
            session.maskingPolicyVersion, session.evidenceRetention, session.deletionPolicyVersion,
            session.versionSetJson, status, 0, false, now, now, 0, failure = failure)

    @Test
    fun publicFailureUsesAWebDtoAndOnlyContractFields() {
        assertEquals(TradingImportFailureResponse::class.java,
            TradingImportSessionResponse::class.java.getDeclaredField("failure").type)
        currentSession = restoreSession(TradingImportStatus.PROCESSING_FAILED,
            TradingImportFailure("TEMPORARY_DEPENDENCY_FAILURE", true, "RETRY", now))
        val response = mapper.readTree(mvc.perform(get("/trading-import-sessions/10"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val failure = response.path("failure")
        assertEquals(setOf("code", "retryable", "userAction", "occurredAt"), failure.propertyNames().toSet())
        assertEquals("TEMPORARY_DEPENDENCY_FAILURE", failure.path("code").asText())
        assertEquals(true, failure.path("retryable").asBoolean())
        assertEquals("RETRY", failure.path("userAction").asText())
        assertEquals(now.toString(), failure.path("occurredAt").asText())
    }

    @Test
    fun createListGetAndDeleteFollowPublicShape() {
        mvc.perform(post("/trading-books/20/imports").header("Idempotency-Key", "create")
            .contentType("application/json")
            .content("""{"sourceTimezone":"UTC","reviewTimezone":"Asia/Seoul","declaredPositionMode":"ONE_WAY"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.status").value("RECEIVED"))
            .andExpect(jsonPath("$.canValidate").value(false))
            .andExpect(jsonPath("$.ownerMemberId").doesNotExist())
        mvc.perform(get("/trading-books/20/imports")).andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(1))
        mvc.perform(get("/trading-import-sessions/10")).andExpect(status().isOk)
            .andExpect(jsonPath("$.missingArtifactRoles.length()").value(2))
        mvc.perform(delete("/trading-import-sessions/10").header("Idempotency-Key", "delete"))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.affectedResources.importSessions").value(1))
    }

    @Test
    fun terminalImportResponsesPreserveQualityNullAndAcceptedReport() {
        currentSession = restoreSession(status = TradingImportStatus.REJECTED,
            failure = TradingImportFailure("REQUIRED_COLUMN_MISSING", false, "NONE", now))
        val rejected = mapper.readTree(mvc.perform(get("/trading-import-sessions/10"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals("REJECTED", rejected.path("status").asText())
        assertEquals(true, rejected.has("qualitySummary"))
        assertEquals(true, rejected.path("qualitySummary").isNull)
        assertEquals("REQUIRED_COLUMN_MISSING", rejected.path("failure").path("code").asText())
        assertEquals(true, rejected.hasNonNull("completedAt"))
        assertEquals(false, rejected.hasNonNull("createdLedgerRevisionId"))

        currentSession = restoreSession(status = TradingImportStatus.PROCESSING_FAILED,
            failure = TradingImportFailure("RESULT_HASH_MISMATCH", false, "WAIT_FOR_RECOVERY", now))
        val failed = mapper.readTree(mvc.perform(get("/trading-import-sessions/10"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals("PROCESSING_FAILED", failed.path("status").asText())
        assertEquals("RESULT_HASH_MISMATCH", failed.path("failure").path("code").asText())
        assertEquals(false, failed.hasNonNull("qualitySummary"))
        assertEquals(false, failed.hasNonNull("createdLedgerRevisionId"))

        currentSession = restoreSession(status = TradingImportStatus.ACCEPTED)
        published = PublishedLedgerSummary("revision-1",
            """{"qualityStatus":"ACCEPTABLE","rowCountsByArtifactRole":{"TRADE_HISTORY":1},"ignoredNonTradingRowCounts":{},"validationIssues":[],"reconciliationStatusCounts":{},"excludedReasonCounts":{}}""",
            """["CLOSED_OUTCOME"]""", now)
        val accepted = mapper.readTree(mvc.perform(get("/trading-import-sessions/10"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals("ACCEPTED", accepted.path("status").asText())
        assertEquals("revision-1", accepted.path("createdLedgerRevisionId").asText())
        assertEquals("ACCEPTABLE", accepted.path("qualitySummary").path("qualityStatus").asText())
        assertEquals("CLOSED_OUTCOME", accepted.path("qualitySummary").path("capability")[0].asText())
    }

    @Test
    fun multipartAcceptsEitherPartOrderAndKeepsObjectKeyPrivate() {
        for (fileFirst in listOf(false, true)) {
            mvc.perform(post("/trading-import-sessions/10/artifacts")
                .header("Idempotency-Key", "upload-" + fileFirst)
                .contentType("multipart/form-data; boundary=testing-boundary")
                .content(body(fileFirst)))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.role").value("TRADE_HISTORY"))
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.wrappedDek").doesNotExist())
        }
    }

    private fun body(fileFirst: Boolean): ByteArray {
        val bytes = "synthetic,record\n".toByteArray()
        val metadata = ("{\"role\":\"TRADE_HISTORY\",\"filename\":\"synthetic.csv\",\"contentType\":\"text/csv\",\"byteSize\":" +
            bytes.size + ",\"sha256\":\"" + sha(bytes) + "\",\"dialectCandidate\":\"AUTO\"}").toByteArray()
        fun part(name: String, contentType: String, value: ByteArray): ByteArray =
            ("--testing-boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\nContent-Type: $contentType\r\n\r\n").toByteArray() +
                value + "\r\n".toByteArray()
        return (if (fileFirst) part("file", "text/csv", bytes) + part("metadata", "application/json", metadata)
            else part("metadata", "application/json", metadata) + part("file", "text/csv", bytes)) +
            "--testing-boundary--\r\n".toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
