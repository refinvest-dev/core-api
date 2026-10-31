package com.refinvest.core.tradingreview.port

import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
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

data class TradingDeletion(
    val id: Long, val owner: MemberId, val targetType: String, val targetId: Long,
    val requestedScope: String, val state: String, val deletionGeneration: Long,
    val accountCount: Long, val bookCount: Long,
    val revokedAt: Instant, val requestedAt: Instant, val updatedAt: Instant,
    val backupPurgeDueAt: Instant,
    val completedAt: Instant? = null,
    val failureCode: String? = null,
    val importSessionCount: Long = 0, val artifactCount: Long = 0,
) {
    companion object {
        fun fromDomain(request: DeletionRequest): TradingDeletion {
            val resources = request.affectedResources
            return TradingDeletion(request.id.value, request.owner, request.scope.targetType.name,
                request.scope.target.value, request.scope.requestedScope, request.state.name, request.deletionGeneration,
                resources.accounts, resources.books, request.revokedAt, request.requestedAt, request.updatedAt,
                request.backupPurgeDueAt, request.completedAt, request.failureCode, resources.importSessions, resources.artifacts)
        }
    }
}

sealed interface IdempotencyClaim {
    data object New : IdempotencyClaim
    data class Replayed(val resourceId: Long) : IdempotencyClaim
}

interface TradingIdempotencyStore {
    fun claim(owner: MemberId, operation: String, key: String, fingerprint: String): IdempotencyClaim
    fun complete(owner: MemberId, operation: String, key: String, resourceId: Long)
}

class TradingFailure(val code: String, val httpStatus: Int) : RuntimeException(code)

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

interface GetTradingDeletionRequestUseCase { fun get(id: Long): TradingDeletion }
