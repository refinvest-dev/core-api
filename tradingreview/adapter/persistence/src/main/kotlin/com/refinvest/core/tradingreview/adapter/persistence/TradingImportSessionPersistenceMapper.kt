package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.*

internal object TradingImportSessionPersistenceMapper {
    fun toRecord(domain: TradingImportSession): TradingImportSessionRecord = TradingImportSessionRecord(
        id = domain.id.value,
        bookId = domain.bookId.value,
        owner = domain.owner.value,
        sourceTimezone = domain.sourceTimezone,
        reviewTimezone = domain.reviewTimezone,
        declaredPositionMode = domain.declaredPositionMode.name,
        dataPolicyVersion = domain.dataPolicyVersion,
        retentionPolicy = domain.retentionPolicy,
        maskingPolicyVersion = domain.maskingPolicyVersion,
        evidenceRetention = domain.evidenceRetention,
        deletionPolicyVersion = domain.deletionPolicyVersion,
        versionSetJson = domain.versionSetJson,
        status = domain.status.name,
        deletionGeneration = domain.deletionGeneration,
        deletionPending = domain.deletionPending,
        createdAt = domain.createdAt,
        updatedAt = domain.updatedAt,
        version = domain.version,
        validationStartedAt = domain.validationStartedAt,
        activeNormalizationRunId = domain.activeNormalizationRunId?.value,
        failure = domain.failure?.let { TradingImportFailureRecord(it.code, it.retryable, it.userAction, it.occurredAt) },
        createdLedgerRevisionId = domain.createdLedgerRevisionId?.value,
        artifacts = domain.artifacts.map(::toRecord),
        normalizationRun = domain.normalizationRun?.let(NormalizationRunPersistenceMapper::toRecord),
        acceptedAt = domain.acceptedAt,
        rejectedAt = domain.rejectedAt,
        rawExpiredAt = domain.rawExpiredAt,
    )

    fun toDomain(record: TradingImportSessionRecord): TradingImportSession = TradingImportSession.restore(
        id = TradingImportSessionId(record.id),
        bookId = TradingBookId(record.bookId),
        owner = MemberId(record.owner),
        sourceTimezone = record.sourceTimezone,
        reviewTimezone = record.reviewTimezone,
        declaredPositionMode = TradingPositionMode.valueOf(record.declaredPositionMode),
        dataPolicyVersion = record.dataPolicyVersion,
        retentionPolicy = record.retentionPolicy,
        maskingPolicyVersion = record.maskingPolicyVersion,
        evidenceRetention = record.evidenceRetention,
        deletionPolicyVersion = record.deletionPolicyVersion,
        versionSetJson = record.versionSetJson,
        status = TradingImportStatus.valueOf(record.status),
        deletionGeneration = record.deletionGeneration,
        deletionPending = record.deletionPending,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt,
        version = record.version,
        validationStartedAt = record.validationStartedAt,
        activeNormalizationRunId = record.activeNormalizationRunId?.let(::NormalizationRunId),
        failure = record.failure?.let { TradingImportFailure(it.code, it.retryable, it.userAction, it.occurredAt) },
        createdLedgerRevisionId = record.createdLedgerRevisionId?.let(::LedgerRevisionId),
        artifacts = record.artifacts.map(::toDomain),
        normalizationRun = record.normalizationRun?.let(NormalizationRunPersistenceMapper::toDomain),
        acceptedAt = record.acceptedAt,
        rejectedAt = record.rejectedAt,
        rawExpiredAt = record.rawExpiredAt,
    )

    fun toRecord(domain: TradingSourceArtifact): TradingSourceArtifactRecord = TradingSourceArtifactRecord(
        id = domain.id.value,
        sessionId = domain.sessionId.value,
        role = domain.role.name,
        filename = domain.filename,
        contentType = domain.contentType,
        byteSize = domain.byteSize,
        sha256 = domain.sha256,
        dialectCandidate = domain.dialectCandidate,
        objectKey = domain.objectKey,
        wrappedDek = domain.wrappedDek,
        keyVersion = domain.keyVersion,
        retentionStatus = domain.retentionStatus.name,
        uploadedAt = domain.uploadedAt,
        rawExpiresAt = domain.rawExpiresAt,
        deletedAt = domain.deletedAt,
        deletionFailure = domain.deletionFailure,
        version = domain.version,
    )

    fun toDomain(record: TradingSourceArtifactRecord): TradingSourceArtifact = TradingSourceArtifact.restore(
        id = TradingSourceArtifactId(record.id),
        sessionId = TradingImportSessionId(record.sessionId),
        role = TradingArtifactRole.valueOf(record.role),
        filename = record.filename,
        contentType = record.contentType,
        byteSize = record.byteSize,
        sha256 = record.sha256,
        dialectCandidate = record.dialectCandidate,
        objectKey = record.objectKey,
        wrappedDek = record.wrappedDek,
        keyVersion = record.keyVersion,
        retentionStatus = TradingArtifactRetentionStatus.valueOf(record.retentionStatus),
        uploadedAt = record.uploadedAt,
        rawExpiresAt = record.rawExpiresAt,
        deletedAt = record.deletedAt,
        deletionFailure = record.deletionFailure,
        version = record.version,
    )

    fun toEntity(domain: TradingImportSession) = toEntity(toRecord(domain))

    fun toEntity(record: TradingImportSessionRecord) = TradingImportSessionJpaEntity(
        id = record.id,
        bookId = record.bookId,
        owner = record.owner,
        sourceTimezone = record.sourceTimezone,
        reviewTimezone = record.reviewTimezone,
        declaredPositionMode = record.declaredPositionMode,
        dataPolicyVersion = record.dataPolicyVersion,
        retentionPolicy = record.retentionPolicy,
        maskingPolicyVersion = record.maskingPolicyVersion,
        evidenceRetention = record.evidenceRetention,
        deletionPolicyVersion = record.deletionPolicyVersion,
        versionSetJson = record.versionSetJson,
        status = record.status,
        deletionGeneration = record.deletionGeneration,
        deletionPending = record.deletionPending,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt,
        version = record.version,
        validationStartedAt = record.validationStartedAt,
        activeNormalizationRunId = record.activeNormalizationRunId?.let(java.util.UUID::fromString),
        createdLedgerRevisionId = record.createdLedgerRevisionId,
        acceptedAt = record.acceptedAt,
        rejectedAt = record.rejectedAt,
        rawExpiredAt = record.rawExpiredAt,
        failureCode = record.failure?.code,
        failureRetryable = record.failure?.retryable,
        failureUserAction = record.failure?.userAction,
        failureOccurredAt = record.failure?.occurredAt,
    )

    fun toRecord(entity: TradingImportSessionJpaEntity) = TradingImportSessionRecord(
        id = entity.id,
        bookId = entity.bookId,
        owner = entity.owner,
        sourceTimezone = entity.sourceTimezone,
        reviewTimezone = entity.reviewTimezone,
        declaredPositionMode = entity.declaredPositionMode,
        dataPolicyVersion = entity.dataPolicyVersion,
        retentionPolicy = entity.retentionPolicy,
        maskingPolicyVersion = entity.maskingPolicyVersion,
        evidenceRetention = entity.evidenceRetention,
        deletionPolicyVersion = entity.deletionPolicyVersion,
        versionSetJson = entity.versionSetJson,
        status = entity.status,
        deletionGeneration = entity.deletionGeneration,
        deletionPending = entity.deletionPending,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        version = entity.version,
        validationStartedAt = entity.validationStartedAt,
        activeNormalizationRunId = entity.activeNormalizationRunId?.toString(),
        failure = entity.failureCode?.let { TradingImportFailureRecord(it, requireNotNull(entity.failureRetryable), requireNotNull(entity.failureUserAction), requireNotNull(entity.failureOccurredAt)) },
        createdLedgerRevisionId = entity.createdLedgerRevisionId,
        acceptedAt = entity.acceptedAt,
        rejectedAt = entity.rejectedAt,
        rawExpiredAt = entity.rawExpiredAt,
    )

    fun apply(record: TradingImportSessionRecord, entity: TradingImportSessionJpaEntity) {
        entity.status = record.status
        entity.deletionGeneration = record.deletionGeneration
        entity.deletionPending = record.deletionPending
        entity.updatedAt = record.updatedAt
        entity.validationStartedAt = record.validationStartedAt
        entity.activeNormalizationRunId = record.activeNormalizationRunId?.let(java.util.UUID::fromString)
        entity.rejectedAt = record.rejectedAt
        entity.rawExpiredAt = record.rawExpiredAt
        entity.failureCode = record.failure?.code
        entity.failureRetryable = record.failure?.retryable
        entity.failureUserAction = record.failure?.userAction
        entity.failureOccurredAt = record.failure?.occurredAt
    }

    fun toEntity(domain: TradingSourceArtifact) = toEntity(toRecord(domain))

    fun toEntity(record: TradingSourceArtifactRecord) = TradingSourceArtifactJpaEntity(
        id = record.id,
        sessionId = record.sessionId,
        role = record.role,
        filename = record.filename,
        contentType = record.contentType,
        byteSize = record.byteSize,
        sha256 = record.sha256,
        dialectCandidate = record.dialectCandidate,
        objectKey = record.objectKey,
        wrappedDek = record.wrappedDek?.copyOf(),
        keyVersion = record.keyVersion,
        retentionStatus = record.retentionStatus,
        uploadedAt = record.uploadedAt,
        rawExpiresAt = record.rawExpiresAt,
        deletedAt = record.deletedAt,
        deletionFailure = record.deletionFailure,
        version = record.version,
    )

    fun toRecord(entity: TradingSourceArtifactJpaEntity) = TradingSourceArtifactRecord(
        id = entity.id,
        sessionId = entity.sessionId,
        role = entity.role,
        filename = entity.filename,
        contentType = entity.contentType,
        byteSize = entity.byteSize,
        sha256 = entity.sha256,
        dialectCandidate = entity.dialectCandidate,
        objectKey = entity.objectKey,
        wrappedDek = entity.wrappedDek?.copyOf(),
        keyVersion = entity.keyVersion,
        retentionStatus = entity.retentionStatus,
        uploadedAt = entity.uploadedAt,
        rawExpiresAt = entity.rawExpiresAt,
        deletedAt = entity.deletedAt,
        deletionFailure = entity.deletionFailure,
        version = entity.version,
    )

    fun apply(record: TradingSourceArtifactRecord, entity: TradingSourceArtifactJpaEntity) {
        entity.retentionStatus = record.retentionStatus
        entity.rawExpiresAt = record.rawExpiresAt
    }

}
