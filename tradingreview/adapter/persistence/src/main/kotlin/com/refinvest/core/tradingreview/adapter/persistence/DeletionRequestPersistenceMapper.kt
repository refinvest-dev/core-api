package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*

internal object DeletionRequestPersistenceMapper {
    fun toRecord(request: DeletionRequest): DeletionRequestRecord {
        val resources = request.affectedResources
        return DeletionRequestRecord(request.id.value, request.owner.value, request.scope.targetType.name,
            request.scope.target.value, request.scope.requestedScope, request.state.name, request.deletionGeneration,
            resources.accounts, resources.books, resources.importSessions, resources.artifacts, request.revokedAt,
            request.requestedAt, request.updatedAt, request.backupPurgeDueAt, request.completedAt,
            request.failureCode, request.attemptCount, request.version,
            request.verifiedAbsent.mapKeys { it.key.name })
    }
    fun toDomain(record: DeletionRequestRecord): DeletionRequest {
        val type = DeletionTargetType.valueOf(record.targetType)
        val target = when (type) {
            DeletionTargetType.TRADING_ACCOUNT -> TradingAccountId(record.targetId)
            DeletionTargetType.TRADING_BOOK -> TradingBookId(record.targetId)
            DeletionTargetType.TRADING_IMPORT_SESSION -> TradingImportSessionId(record.targetId)
            DeletionTargetType.TRADING_SOURCE_ARTIFACT -> TradingSourceArtifactId(record.targetId)
        }
        return DeletionRequest.restore(DeletionRequestId(record.id), MemberId(record.ownerId),
            DeletionScope.restore(type, target, record.requestedScope), DeletionRequestState.valueOf(record.state),
            record.deletionGeneration, DeletionAffectedResources(record.accountCount, record.bookCount,
                record.importSessionCount, record.artifactCount), record.revokedAt, record.requestedAt,
            record.updatedAt, record.backupPurgeDueAt, record.completedAt, record.failureCode, record.attemptCount,
            record.version, record.verifiedAbsent.mapKeys { DeletionPurgeComponent.valueOf(it.key) })
    }
}
