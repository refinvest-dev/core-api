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
        activeNormalizationRunId = domain.activeNormalizationRunId,
        failure = domain.failure?.let { TradingImportFailureRecord(it.code, it.retryable, it.userAction, it.occurredAt) },
        createdLedgerRevisionId = domain.createdLedgerRevisionId?.value,
        artifacts = domain.artifacts.map(::toRecord),
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
        activeNormalizationRunId = record.activeNormalizationRunId,
        failure = record.failure?.let { TradingImportFailure(it.code, it.retryable, it.userAction, it.occurredAt) },
        createdLedgerRevisionId = record.createdLedgerRevisionId?.let(::LedgerRevisionId),
        artifacts = record.artifacts.map(::toDomain),
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

}
