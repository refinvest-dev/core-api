package com.refinvest.core.tradingreview.domain

import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.time.ZoneId

class TradingImportSession private constructor(
    id: TradingImportSessionId,
    val bookId: TradingBookId,
    val owner: MemberId,
    val sourceTimezone: String,
    val reviewTimezone: String,
    val declaredPositionMode: TradingPositionMode,
    val dataPolicyVersion: String,
    val retentionPolicy: String,
    val maskingPolicyVersion: String,
    val evidenceRetention: String,
    val deletionPolicyVersion: String,
    val versionSetJson: String,
    val status: TradingImportStatus,
    val deletionGeneration: Long,
    val deletionPending: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val validationStartedAt: Instant? = null,
    val activeNormalizationRunId: NormalizationRunId? = null,
    val failure: TradingImportFailure? = null,
    artifacts: List<TradingSourceArtifact> = emptyList(),
    val createdLedgerRevisionId: LedgerRevisionId? = null,
    val normalizationRun: NormalizationRun? = null,
) : AggregateRoot<TradingImportSessionId>(id) {
    val artifacts: List<TradingSourceArtifact> = java.util.Collections.unmodifiableList(artifacts.toList())

    init {
        require(sourceTimezone in ZoneId.getAvailableZoneIds() && reviewTimezone in ZoneId.getAvailableZoneIds()) { "Invalid IANA timezone" }
        require(version >= 0 && deletionGeneration >= 0) { "Invalid session version or generation" }
        require(!updatedAt.isBefore(createdAt)) { "Session update precedes creation" }
        require(!deletionPending || deletionGeneration > 0) { "Deletion requires a new generation" }
        require(listOf(dataPolicyVersion, retentionPolicy, maskingPolicyVersion, evidenceRetention,
            deletionPolicyVersion, versionSetJson).all { it.isNotBlank() }) { "Missing policy or version pin" }
        require(declaredPositionMode == TradingPositionMode.ONE_WAY && evidenceRetention == "BOOK_LIFECYCLE")
        require(this.artifacts.size <= TradingArtifactRole.entries.size && this.artifacts.all { it.sessionId == id }) { "Foreign artifact" }
        require(this.artifacts.map { it.id }.distinct().size == this.artifacts.size &&
            this.artifacts.map { it.role }.distinct().size == this.artifacts.size) { "Duplicate artifact slot" }
        require(status !in setOf(TradingImportStatus.RECEIVED, TradingImportStatus.ACCEPTED) || failure == null)
        require(status !in setOf(TradingImportStatus.REJECTED, TradingImportStatus.PROCESSING_FAILED) || failure != null)
        require(status != TradingImportStatus.RECEIVED || (activeNormalizationRunId == null && validationStartedAt == null))
        require(status != TradingImportStatus.VALIDATING || (activeNormalizationRunId != null && validationStartedAt != null && failure == null)) { "Incomplete validation state" }
        require(normalizationRun == null || (normalizationRun.id == activeNormalizationRunId &&
            normalizationRun.input.importSessionId == id && normalizationRun.input.tradingBookId == bookId &&
            normalizationRun.input.ownerId == owner.value)) { "Foreign normalization run" }
        require(validationStartedAt == null || !validationStartedAt.isBefore(createdAt))
        require(createdLedgerRevisionId == null || status == TradingImportStatus.ACCEPTED) { "Only accepted imports have a Revision" }
    }

    fun recordUpload(artifactId: TradingSourceArtifactId, role: TradingArtifactRole, contentType: String,
                     byteSize: Long, sha256: String, dialectCandidate: String, objectKey: String,
                     wrappedDek: ByteArray, keyVersion: String, now: Instant): TradingImportSession {
        require(canUpload()) { "Session cannot receive artifacts" }
        require(artifacts.none { it.role == role || it.id == artifactId }) { "Artifact slot already filled" }
        val artifact = TradingSourceArtifact.restore(artifactId, id, role, null, contentType, byteSize,
            sha256, dialectCandidate, objectKey, wrappedDek, keyVersion, TradingArtifactRetentionStatus.ACTIVE,
            now, now.plusSeconds(86400), null, null, 0)
        return copy(artifacts = artifacts + artifact, updatedAt = now)
    }

    fun requestArtifactDeletion(artifactId: TradingSourceArtifactId, now: Instant): TradingImportSession {
        require(artifacts.any { it.id == artifactId }) { "Artifact does not belong to session" }
        return requestRawDeletion(now).copy(artifacts = artifacts.map {
            if (it.id == artifactId) it.requestDeletion() else it
        })
    }

    fun canUpload() = status == TradingImportStatus.RECEIVED && !deletionPending

    fun missingArtifactRoles(artifacts: List<TradingSourceArtifact>): Set<TradingArtifactRole> =
        TradingArtifactRole.entries.toSet() - artifacts.filter { it.sessionId == id && it.retentionStatus == TradingArtifactRetentionStatus.ACTIVE }
            .map { it.role }.toSet()

    fun canValidate(artifacts: List<TradingSourceArtifact>) = canUpload() &&
        artifacts.all { it.sessionId == id } && artifacts.map { it.role }.distinct().size == artifacts.size &&
        missingArtifactRoles(artifacts).isEmpty()

    fun admitNormalization(
        runId: NormalizationRunId, input: NormalizationInput, logicalKey: String, attemptToken: String,
        identity: NormalizationInputIdentity, pinnedVersions: Map<*, *>,
        deliveryPolicy: NormalizationResultDeliveryPolicy, resultPin: NormalizationResultVersionPin, now: Instant,
    ): TradingImportSession {
        val pinnedInput = input.copy(artifacts = input.artifacts.toList())
        require(!deletionPending && status in setOf(TradingImportStatus.RECEIVED, TradingImportStatus.PROCESSING_FAILED))
        require(pinnedInput.importSessionId == id && pinnedInput.tradingBookId == bookId && pinnedInput.ownerId == owner.value)
        require(pinnedInput.deletionGeneration == deletionGeneration && pinnedInput.positionMode == declaredPositionMode.name &&
            pinnedInput.sourceTimezone == sourceTimezone && pinnedInput.reviewTimezone == reviewTimezone &&
            pinnedInput.dataPolicyVersion == dataPolicyVersion && pinnedInput.versionSetJson == versionSetJson) { "Changed session input pin" }
        require(artifacts.size == 2 && artifacts.all { it.rawReparseAvailable(now) }) { "Active source artifacts required" }
        val expectedArtifacts = artifacts.map { NormalizationArtifactInput(it.id, it.role, it.sha256,
            it.byteSize, it.contentType, it.dialectCandidate) }.sortedBy { it.role.name }
        require(pinnedInput.artifacts.sortedBy { it.role.name } == expectedArtifacts) { "Changed artifact identity" }
        val expected = NormalizationInputIdentity.fromPinnedInput(pinnedInput, pinnedVersions)
        require(NormalizationInputCanonicalizer.canonicalize(expected).contentEquals(
            NormalizationInputCanonicalizer.canonicalize(identity))) { "Changed normalization identity" }
        require(logicalKey == NormalizationInputCanonicalizer.logicalKey(owner.value,
            NormalizationInputCanonicalizer.hash(identity), deletionGeneration, resultPin)) { "Changed logical key" }
        val previous = normalizationRun
        if (status == TradingImportStatus.PROCESSING_FAILED) {
            require(failure?.retryable == true && previous != null && previous.id == activeNormalizationRunId &&
                previous.status == NormalizationRunStatus.TERMINAL_FAILURE) { "No retryable terminal run" }
            require(previous.inputIdentity != null && previous.inputFingerprint == NormalizationInputCanonicalizer.hash(identity) &&
                NormalizationInputCanonicalizer.canonicalize(previous.inputIdentity).contentEquals(
                    NormalizationInputCanonicalizer.canonicalize(identity)) &&
                previous.input.artifacts.sortedBy { it.role.name } == expectedArtifacts &&
                previous.input.deletionGeneration == deletionGeneration && previous.resultVersionPin == resultPin && previous.logicalKey == logicalKey) { "Changed retry pin" }
            require(previous.id != runId)
        } else require(previous == null && activeNormalizationRunId == null)
        val run = NormalizationRun.create(runId, pinnedInput, NormalizationInputCanonicalizer.hash(identity), logicalKey,
            attemptToken, identity, deliveryPolicy, resultPin, previous?.id, now)
        return copy(status = TradingImportStatus.VALIDATING, activeNormalizationRunId = runId,
            validationStartedAt = now, failure = null, updatedAt = now, normalizationRun = run)
    }

    fun failValidation(runId: String, generation: Long, cause: TradingImportFailure, now: Instant): TradingImportSession? =
        if (status == TradingImportStatus.VALIDATING && activeNormalizationRunId?.value == runId &&
            deletionGeneration == generation && !deletionPending)
            copy(status = TradingImportStatus.PROCESSING_FAILED, failure = cause, updatedAt = now) else null

    fun rejectValidation(runId: String, generation: Long, cause: TradingImportFailure, now: Instant): TradingImportSession? =
        if (status == TradingImportStatus.VALIDATING && activeNormalizationRunId?.value == runId &&
            deletionGeneration == generation && !deletionPending)
            copy(status = TradingImportStatus.REJECTED, failure = cause, updatedAt = now) else null

    private fun requestRawDeletion(now: Instant): TradingImportSession {
        require(!deletionPending) { "Session deletion already requested" }
        return copy(deletionGeneration = Math.addExact(deletionGeneration, 1),
            status = if (status == TradingImportStatus.VALIDATING) TradingImportStatus.PROCESSING_FAILED else status,
            failure = if (status == TradingImportStatus.VALIDATING)
                TradingImportFailure("SOURCE_ARTIFACT_DELETED", false, "REUPLOAD_REQUIRED", now) else failure,
            updatedAt = now)
    }

    fun requestDeletion(now: Instant): TradingImportSession {
        require(!deletionPending) { "Deletion already requested" }
        return copy(deletionGeneration = Math.addExact(deletionGeneration, 1), deletionPending = true, updatedAt = now,
            artifacts = artifacts.map { if (it.retentionStatus in setOf(TradingArtifactRetentionStatus.ACTIVE,
                TradingArtifactRetentionStatus.RETENTION_SCHEDULED)) it.requestDeletion() else it })
    }

    private fun copy(
        status: TradingImportStatus = this.status,
        deletionGeneration: Long = this.deletionGeneration,
        deletionPending: Boolean = this.deletionPending,
        updatedAt: Instant = this.updatedAt,
        validationStartedAt: Instant? = this.validationStartedAt,
        activeNormalizationRunId: NormalizationRunId? = this.activeNormalizationRunId,
        failure: TradingImportFailure? = this.failure,
        artifacts: List<TradingSourceArtifact> = this.artifacts,
        normalizationRun: NormalizationRun? = this.normalizationRun,
    ) = TradingImportSession(id, bookId, owner, sourceTimezone, reviewTimezone, declaredPositionMode,
        dataPolicyVersion, retentionPolicy, maskingPolicyVersion, evidenceRetention, deletionPolicyVersion,
        versionSetJson, status, deletionGeneration, deletionPending, createdAt, updatedAt, version,
        validationStartedAt, activeNormalizationRunId, failure, artifacts, createdLedgerRevisionId, normalizationRun)

    companion object {
        fun restore(
            id: TradingImportSessionId,
            bookId: TradingBookId,
            owner: MemberId,
            sourceTimezone: String,
            reviewTimezone: String,
            declaredPositionMode: TradingPositionMode,
            dataPolicyVersion: String,
            retentionPolicy: String,
            maskingPolicyVersion: String,
            evidenceRetention: String,
            deletionPolicyVersion: String,
            versionSetJson: String,
            status: TradingImportStatus,
            deletionGeneration: Long,
            deletionPending: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
            validationStartedAt: Instant? = null,
            activeNormalizationRunId: NormalizationRunId? = null,
            failure: TradingImportFailure? = null,
            artifacts: List<TradingSourceArtifact> = emptyList(),
            createdLedgerRevisionId: LedgerRevisionId? = null,
            normalizationRun: NormalizationRun? = null,
        ) = TradingImportSession(id, bookId, owner, sourceTimezone, reviewTimezone, declaredPositionMode,
            dataPolicyVersion, retentionPolicy, maskingPolicyVersion, evidenceRetention, deletionPolicyVersion,
            versionSetJson, status, deletionGeneration, deletionPending, createdAt, updatedAt, version,
            validationStartedAt, activeNormalizationRunId, failure, artifacts, createdLedgerRevisionId, normalizationRun)

        fun create(id: TradingImportSessionId, book: TradingBook, sourceTimezone: String,
                   reviewTimezone: String, mode: TradingPositionMode, policy: TradingDataPolicy,
                   versionSetJson: String, now: Instant): TradingImportSession {
            require(book.status == TradingLifecycleStatus.ACTIVE) { "Book is not active" }
            require(sourceTimezone in ZoneId.getAvailableZoneIds()) { "Invalid source timezone" }
            require(reviewTimezone in ZoneId.getAvailableZoneIds()) { "Invalid review timezone" }
            require(mode == TradingPositionMode.ONE_WAY && mode == book.positionMode) { "Unsupported position mode" }
            require(versionSetJson.isNotBlank()) { "Exact version set is required" }
            return TradingImportSession(id, book.id, book.owner, sourceTimezone, reviewTimezone, mode,
                policy.dataPolicyVersion, policy.retentionPolicy, policy.maskingPolicyVersion,
                "BOOK_LIFECYCLE", policy.deletionPolicyVersion, versionSetJson,
                TradingImportStatus.RECEIVED, 0, false, now, now, 0)
        }
    }
}
