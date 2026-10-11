package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TradingImportControllerTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val mapper = JsonMapper.builder().build()
    private val session = TradingImportSession(TradingImportSessionId(10), TradingBookId(20), MemberId(30),
        "UTC", "Asia/Seoul", TradingPositionMode.ONE_WAY, "TRADING_DATA_POLICY_V1", "TRADING_RETENTION_V1",
        "SOURCE_EVIDENCE_MASK_V1", "BOOK_LIFECYCLE", "TRADING_DELETION_V1",
        "{\"versionSetHash\":\"exact\"}", TradingImportStatus.RECEIVED, 0, false, now, now, 0)
    private val artifacts = mutableListOf<TradingSourceArtifact>()
    private val deletion = TradingDeletion(90, MemberId(30), "TRADING_IMPORT_SESSION", 10,
        "IMPORT_SESSION_SUBTREE", "REQUESTED", 1, 0, 0, now, now, now, now.plusSeconds(86400),
        importSessionCount = 1, artifactCount = 2)
    private val imports = object : TradingImportUseCase {
        override fun create(bookId: TradingBookId, sourceTimezone: String, reviewTimezone: String,
                            mode: TradingPositionMode, dataPolicyVersion: String?, clientRequestReference: String?,
                            key: String) = session
        override fun get(id: TradingImportSessionId) = TradingImportSessionView(session, artifacts.toList())
        override fun list(bookId: TradingBookId, page: Int, size: Int) = TradingPage(listOf(get(session.id)), page, size, 1)
        override fun upload(id: TradingImportSessionId, role: TradingArtifactRole, filename: String,
                            contentType: String, declaredSize: Long, declaredSha256: String,
                            dialectCandidate: String, source: InputStream, key: String) =
            uploadDeferred(id, source, key) {
                TradingUploadMetadata(role, filename, contentType, declaredSize, declaredSha256, dialectCandidate)
            }
        override fun uploadDeferred(id: TradingImportSessionId, source: InputStream, key: String,
                                    metadata: () -> TradingUploadMetadata): UploadArtifactResult {
            val bytes = source.readAllBytes()
            val value = metadata()
            assertEquals(10L, id.value)
            assertEquals(bytes.size.toLong(), value.byteSize)
            assertEquals(sha(bytes), value.sha256)
            val artifact = TradingSourceArtifact(TradingSourceArtifactId(40 + artifacts.size.toLong()), id,
                value.role, value.filename, value.contentType, value.byteSize, value.sha256, value.dialectCandidate,
                "objects/00000000-0000-0000-0000-000000000040", byteArrayOf(1), "test-v1",
                TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(86400), null, null, 0)
            artifacts.add(artifact)
            return UploadArtifactResult(artifact, true)
        }
        override fun delete(id: TradingImportSessionId, key: String) = deletion
        override fun deleteArtifact(id: TradingSourceArtifactId, key: String) = deletion
    }
    private val mvc = MockMvcBuilders.standaloneSetup(
        TradingBookImportController(imports, mapper, Clock.fixed(now, ZoneOffset.UTC)),
        TradingImportSessionController(imports, mapper, Clock.fixed(now, ZoneOffset.UTC)),
        TradingSourceArtifactController(imports),
    ).setControllerAdvice(TradingReviewExceptionHandler()).build()

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
