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
            request.verifiedAbsent.mapKeys { it.key.name }, resources.ledgerRevisions, resources.analysisRuns,
            resources.reprocessingRuns, resources.snapshotStatus.name)
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
                record.importSessionCount, record.artifactCount, record.ledgerRevisionCount, record.analysisRunCount,
                record.reprocessingRunCount, DeletionCountSnapshotStatus.valueOf(record.countSnapshotStatus)), record.revokedAt, record.requestedAt,
            record.updatedAt, record.backupPurgeDueAt, record.completedAt, record.failureCode, record.attemptCount,
            record.version, record.verifiedAbsent.mapKeys { DeletionPurgeComponent.valueOf(it.key) })
    }
    fun toEntity(request: DeletionRequest): DeletionRequestJpaEntity {
        val record = toRecord(request)
        return DeletionRequestJpaEntity(
            id = record.id,
            ownerId = record.ownerId,
            targetType = record.targetType,
            targetId = record.targetId,
            requestedScope = record.requestedScope,
            state = record.state,
            deletionGeneration = record.deletionGeneration,
            accountCount = record.accountCount,
            bookCount = record.bookCount,
            importSessionCount = record.importSessionCount,
            artifactCount = record.artifactCount,
            revokedAt = record.revokedAt,
            requestedAt = record.requestedAt,
            updatedAt = record.updatedAt,
            backupPurgeDueAt = record.backupPurgeDueAt,
            completedAt = record.completedAt,
            failureCode = record.failureCode,
            attemptCount = record.attemptCount,
            version = record.version,
            ledgerRevisionCount = record.ledgerRevisionCount,
            analysisRunCount = record.analysisRunCount,
            reprocessingRunCount = record.reprocessingRunCount,
            countSnapshotStatus = record.countSnapshotStatus,
        )
    }

    fun toRecord(entity: DeletionRequestJpaEntity) = DeletionRequestRecord(
        id = entity.id,
        ownerId = entity.ownerId,
        targetType = entity.targetType,
        targetId = entity.targetId,
        requestedScope = entity.requestedScope,
        state = entity.state,
        deletionGeneration = entity.deletionGeneration,
        accountCount = entity.accountCount,
        bookCount = entity.bookCount,
        importSessionCount = entity.importSessionCount,
        artifactCount = entity.artifactCount,
        revokedAt = entity.revokedAt,
        requestedAt = entity.requestedAt,
        updatedAt = entity.updatedAt,
        backupPurgeDueAt = entity.backupPurgeDueAt,
        completedAt = entity.completedAt,
        failureCode = entity.failureCode,
        attemptCount = entity.attemptCount,
        version = entity.version,
        ledgerRevisionCount = entity.ledgerRevisionCount,
        analysisRunCount = entity.analysisRunCount,
        reprocessingRunCount = entity.reprocessingRunCount,
        countSnapshotStatus = entity.countSnapshotStatus,
    )

}
