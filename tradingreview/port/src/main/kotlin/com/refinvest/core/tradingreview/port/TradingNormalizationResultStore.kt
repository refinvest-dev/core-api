package com.refinvest.core.tradingreview.port

import com.refinvest.core.tradingreview.domain.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.NormalizationRun
import java.time.Duration
import java.time.Instant

data class NormalizationStagedItem(
    val sectionType: String,
    val identity: String,
    val contentHash: String,
    val publicJson: String,
)

class NormalizationResultStoreFailure(val safeCode: String) : RuntimeException(safeCode)

/** Transactional result state. Calls are made within the application transaction template. */
interface TradingNormalizationResultStore {
    fun claimReady(now: Instant, lease: Duration, workerId: String): NormalizationDispatch?
    /** Must be called inside the same transaction as each staging mutation. */
    fun hasReadyLease(dispatchId: String, workerId: String, now: Instant): Boolean
    fun retryReady(dispatchId: String, workerId: String, code: String, next: Instant, now: Instant): Boolean
    fun beginStage(run: NormalizationRun, descriptor: NormalizationResultDescriptor, now: Instant)
    fun loadStageDescriptor(run: NormalizationRun, dispatch: NormalizationDispatch): NormalizationResultDescriptor?
    /** Small immutable root metadata; section items remain in the keyed staging table. */
    fun saveStageBase(runId: String, baseJson: String)
    fun loadStageBase(runId: String): String?
    fun stageItem(runId: String, item: NormalizationStagedItem)
    fun stageItems(runId: String, items: List<NormalizationStagedItem>) {
        items.forEach { stageItem(runId, it) }
    }
    /** Yields a section in JCS UTF-16 identity order, without retaining all rows in the caller. */
    fun visitItems(runId: String, sectionType: String, batchSize: Int,
                   accept: (NormalizationStagedItem) -> Unit)
    /** Checks all staged evidence/record/unit references with database joins. */
    fun hasInvalidEvidence(run: NormalizationRun): Boolean
    /** Checks V2 unit/allocation membership and canonical evidence relationships. */
    fun hasInvalidSupplement(runId: String): Boolean
    fun stagedCounts(runId: String): Map<String, Long>
    fun markValidated(runId: String, now: Instant)
    fun discardStage(runId: String)
    fun cleanupObsoleteStages(limit: Int): Int
    fun cleanupAcknowledgementsForDeletedSessions(limit: Int): Int
    fun publish(run: NormalizationRun, dispatch: NormalizationDispatch, workerId: String,
                descriptor: NormalizationResultDescriptor, manifestJson: String, coverageJson: String,
                capabilityJson: String, qualityJson: String, ledgerContentHash: String,
                now: Instant): Boolean
    /** Records a deletion-generation tombstone and its durable discard ACK in one transaction. */
    fun discardStale(run: NormalizationRun, dispatch: NormalizationDispatch, workerId: String,
                     descriptor: NormalizationResultDescriptor, now: Instant): Boolean
    fun claimAck(now: Instant, lease: Duration, workerId: String): NormalizationPayloadAcknowledgement?
    fun completeAck(key: String, workerId: String, now: Instant): Boolean
    fun retryAck(key: String, workerId: String, next: Instant, now: Instant): Boolean
    fun failAck(key: String, workerId: String, code: String, now: Instant): Boolean
}
