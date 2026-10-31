package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.LedgerRevision
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.ledger.*
import com.refinvest.core.tradingreview.port.normalization.NormalizationDispatchLease
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper

internal class TradingLedgerTestStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper,
    private val store: JdbcTradingNormalizationResultStore = JdbcTradingNormalizationResultStore(jdbc, mapper),
) : TradingNormalizationResultStore by store,
    TradingLedgerPublicationReader by JdbcTradingLedgerPublicationReader(jdbc), TradingLedgerPublication {
    override fun publish(publication: LedgerPublication): Boolean = publisher(publication.lease.observedAt).publish(publication)
    fun publisher(at: Instant) = JdbcLedgerRevisionPersistenceAdapter(jdbc, mapper,
        Clock.fixed(at, ZoneOffset.UTC), JdbcTradingNormalizationTransitionGate(jdbc), store)
    fun publish(run: NormalizationRun, dispatch: NormalizationDispatch, workerId: String,
                descriptor: NormalizationResultDescriptor, manifestJson: String, coverageJson: String,
                capabilityJson: String, qualityJson: String, ledgerContentHash: String, now: Instant): Boolean =
        publish(request(run, dispatch, workerId, descriptor, manifestJson, coverageJson, capabilityJson,
            qualityJson, ledgerContentHash, now))
    fun request(run: NormalizationRun, dispatch: NormalizationDispatch, workerId: String,
                descriptor: NormalizationResultDescriptor, manifestJson: String, coverageJson: String,
                capabilityJson: String, qualityJson: String, ledgerContentHash: String, now: Instant): LedgerPublication {
        val counts = descriptor.counts.mapValues { (_, value) -> (value as Number).toLong() }
        val digests = descriptor.sectionDigests.map { value ->
            val item = value as Map<*, *>
            LedgerSectionDigest(item["sectionType"] as String, (item["itemCount"] as Number).toLong(),
                item["hashSpecificationVersion"] as String, item["sectionHash"] as String)
        }
        val revision = LedgerRevision.prepare(LedgerRevisionId(UUID.randomUUID()), MemberId(run.input.ownerId),
            run.id, run.input.tradingBookId, run.input.importSessionId, requireNotNull(run.inputIdentity),
            LedgerRevisionResult(run.resultVersionPin, descriptor.resultHash, ledgerContentHash,
                descriptor.reconciliationManifestHash, counts, digests),
            LedgerRevisionMetadata(manifestJson, coverageJson, capabilityJson, qualityJson))
        return LedgerPublication(revision, run, dispatch, descriptor, NormalizationDispatchLease(dispatch.id, workerId, now))
    }
}
