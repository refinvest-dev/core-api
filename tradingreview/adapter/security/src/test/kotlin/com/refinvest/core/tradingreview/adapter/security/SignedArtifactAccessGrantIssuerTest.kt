package com.refinvest.core.tradingreview.adapter.security

import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactGrantBinding
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantCandidate
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantIssueRecord
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantIssueStore
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.transaction.support.TransactionSynchronizationManager

class SignedArtifactAccessGrantIssuerTest {
    @Test
    fun `issuance checks storage before recording a bound five-minute credential at the new time`() {
        val fixture = Fixture()
        val grant = fixture.issue()
        assertEquals(listOf("load", "exists", "record"), fixture.calls)
        val record = assertNotNull(fixture.record)
        assertEquals(fixture.start.plusSeconds(2), record.createdAt)
        assertEquals(record.createdAt.plusSeconds(300), grant.expiresAt)
        assertEquals(grant.expiresAt, record.expiresAt)
        assertEquals(record.tokenHash, fixture.tokens.verifiedHash(grant.accessUri.substringAfterLast('/')))
        assertEquals(fixture.artifact, record.binding.artifact)
        assertEquals("objects/synthetic", record.objectKey)
    }

    @Test
    fun `lost eligibility after storage does not return the generated credential`() {
        val fixture = Fixture(recordEligible = false)
        assertEquals("SOURCE_ARTIFACT_EXPIRED", assertFailsWith<TradingFailure> { fixture.issue() }.code)
        assertEquals(listOf("load", "exists", "record"), fixture.calls)
    }

    @Test
    fun `descriptor drift is rejected before storage or credential recording`() {
        val fixture = Fixture(descriptorMatches = false)
        assertEquals("ARTIFACT_IDENTITY_CONFLICT", assertFailsWith<TradingFailure> { fixture.issue() }.code)
        assertEquals(listOf("load"), fixture.calls)
        assertNull(fixture.record)
    }

    private class Fixture(private val recordEligible: Boolean = true, private val descriptorMatches: Boolean = true) {
        val start = Instant.parse("2026-06-01T00:00:00Z")
        private var observed = start
        val calls = mutableListOf<String>()
        var record: ArtifactGrantIssueRecord? = null
        val artifact = NormalizationArtifactInput(TradingSourceArtifactId(51), TradingArtifactRole.TRADE_HISTORY,
            "a".repeat(64), 20, "text/csv", "AUTO")
        val tokens = HmacArtifactGrantTokens("https://core.test", "synthetic-internal-key")
        private val store = object : ArtifactGrantIssueStore {
            override fun loadEligible(binding: ArtifactGrantBinding, observedAt: Instant): ArtifactGrantCandidate {
                calls += "load"
                assertEquals(start, observedAt)
                return ArtifactGrantCandidate("objects/synthetic", 20, if (descriptorMatches) artifact.sha256 else "b".repeat(64),
                    artifact.mediaType, artifact.role.name)
            }
            override fun recordIfEligible(record: ArtifactGrantIssueRecord): Boolean {
                calls += "record"
                this@Fixture.record = record
                return recordEligible
            }
        }
        private val storage = Proxy.newProxyInstance(RawArtifactStorage::class.java.classLoader,
            arrayOf(RawArtifactStorage::class.java)) { _, method, args ->
            assertEquals("exists", method.name)
            assertEquals("objects/synthetic", args!![0])
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            calls += "exists"
            observed = start.plusSeconds(2)
            true
        } as RawArtifactStorage
        private val clock = object : Clock() {
            override fun instant() = observed
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        fun issue() = SignedArtifactAccessGrantIssuer(store, tokens, storage, clock).issueReadGrant(artifact,
            "00000000-0000-0000-0000-000000000001", TradingImportSessionId(41),
            "00000000-0000-0000-0000-000000000002", 0)
    }
}
