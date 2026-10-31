package com.refinvest.core.tradingreview.domain

import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant

/** Immutable content descriptor. Large membership and manifest collections stay in bounded storage. */
class LedgerRevision private constructor(
    id: LedgerRevisionId,
    val owner: MemberId,
    val runId: NormalizationRunId,
    val bookId: TradingBookId,
    val importSessionId: TradingImportSessionId,
    val inputIdentity: NormalizationInputIdentity,
    val result: LedgerRevisionResult,
    val metadata: LedgerRevisionMetadata,
    val publication: LedgerPublicationStamp?,
) : AggregateRoot<LedgerRevisionId>(id) {
    val inputSetHash: String = NormalizationInputCanonicalizer.hash(inputIdentity)
    init {
        require(owner.value > 0 && bookId.value > 0 && importSessionId.value > 0)
        require(inputIdentity.tradingBookId == bookId.value.toString() &&
            inputIdentity.sourceImportSessionIds == listOf(importSessionId.value.toString())) { "Revision input scope mismatch" }
    }

    /** Assign only the serialized Book ordinal and publication time; content is never rewritten. */
    fun publish(number: Long, at: Instant): LedgerRevision {
        check(publication == null) { "Revision already published" }
        return LedgerRevision(id, owner, runId, bookId, importSessionId, inputIdentity,
            result, metadata, LedgerPublicationStamp(number, at))
    }

    companion object {
        fun prepare(id: LedgerRevisionId, owner: MemberId, runId: NormalizationRunId,
                    bookId: TradingBookId, importSessionId: TradingImportSessionId,
                    inputIdentity: NormalizationInputIdentity, result: LedgerRevisionResult,
                    metadata: LedgerRevisionMetadata): LedgerRevision =
            LedgerRevision(id, owner, runId, bookId, importSessionId, inputIdentity, result, metadata, null)

        fun restore(id: LedgerRevisionId, owner: MemberId, runId: NormalizationRunId,
                    bookId: TradingBookId, importSessionId: TradingImportSessionId,
                    inputIdentity: NormalizationInputIdentity, result: LedgerRevisionResult,
                    metadata: LedgerRevisionMetadata, publication: LedgerPublicationStamp): LedgerRevision =
            LedgerRevision(id, owner, runId, bookId, importSessionId, inputIdentity, result, metadata, publication)
    }
}
