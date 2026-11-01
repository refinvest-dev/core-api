package com.refinvest.core.tradingreview.port

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import java.time.Instant

interface TradingMemberProvider { fun currentMemberId(): MemberId }
interface TradingIdGenerator {
    fun accountId(): TradingAccountId
    fun bookId(): TradingBookId
    fun importSessionId(): TradingImportSessionId = error("Import ID generator is not configured")
    fun sourceArtifactId(): TradingSourceArtifactId = error("Artifact ID generator is not configured")
    fun deletionId(): DeletionRequestId
}

data class TradingPage<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

sealed interface IdempotencyClaim {
    data object New : IdempotencyClaim
    data class Replayed(val resourceId: Long) : IdempotencyClaim
}

interface TradingIdempotencyStore {
    fun claim(owner: MemberId, operation: String, key: String, fingerprint: String): IdempotencyClaim
    fun complete(owner: MemberId, operation: String, key: String, resourceId: Long)
}

interface TradingAccountUseCase {
    fun create(venue: TradingVenue, displayName: String, key: String): TradingAccount
    fun get(id: TradingAccountId): TradingAccount
    fun list(page: Int, size: Int): TradingPage<TradingAccount>
    fun delete(id: TradingAccountId, key: String): TradingDeletion
}

interface TradingBookUseCase {
    fun create(accountId: TradingAccountId, displayName: String, productFamily: TradingProductFamily,
               positionMode: TradingPositionMode, settlementAsset: TradingSettlementAsset,
               reviewTimezone: String, key: String): TradingBook
    fun get(id: TradingBookId): TradingBook
    fun list(accountId: TradingAccountId, page: Int, size: Int): TradingPage<TradingBook>
    fun delete(id: TradingBookId, key: String): TradingDeletion
}
