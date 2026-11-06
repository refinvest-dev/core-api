package com.refinvest.core.tradingreview.application.normalization

import com.refinvest.core.tradingreview.application.NormalizationInputFingerprint
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationInput
import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationDispatchStatus
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationInputIdentity
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultVersionPin
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.outbound.identity.TradingNormalizationIdGenerator
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingNormalizationSettings
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationAdmission
import java.time.Instant
import org.springframework.stereotype.Component
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/** Builds a pinned aggregate transition and dispatch; it does not acquire locks or write rows. */
@Component
class NormalizationAdmissionFactory(
    private val versions: TradingVersionPins,
    private val settings: TradingNormalizationSettings,
    private val mapper: ObjectMapper,
    private val identifiers: TradingNormalizationIdGenerator,
) {
    private val strictMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    fun pinnedVersions(session: TradingImportSession, requestedVersions: String?): String {
        val pinnedVersions = canonical(session.versionSetJson)
        if (requestedVersions != null && canonical(requestedVersions) != pinnedVersions)
            throw TradingFailure("VERSION_UNAVAILABLE", TradingFailureKind.INPUT_REJECTED)
        return pinnedVersions
    }

    fun requireExecutable(pinnedVersions: String) {
        if (canonical(versions.currentJson()) != pinnedVersions)
            throw TradingFailure("VERSION_UNAVAILABLE", TradingFailureKind.INPUT_REJECTED)
    }

    fun validation(context: NormalizationAdmissionContext, pinnedVersions: String, now: Instant): NormalizationAdmission =
        prepare(context, pinnedVersions, now, retry = false)

    fun retry(context: NormalizationAdmissionContext, pinnedVersions: String, now: Instant): NormalizationAdmission =
        prepare(context, pinnedVersions, now, retry = true)

    private fun prepare(context: NormalizationAdmissionContext, pinnedVersions: String,
                        now: Instant, retry: Boolean): NormalizationAdmission {
        val (book, session) = context
        val owner = session.owner
        val source = session.artifacts
        val prepared = TradingArtifactRole.entries.map { role ->
            val matches = source.filter { it.role == role && it.retentionStatus == TradingArtifactRetentionStatus.ACTIVE }
            if (matches.isEmpty()) throw TradingFailure(
                if (retry) "SOURCE_ARTIFACT_EXPIRED" else "MISSING_REQUIRED_ARTIFACT",
                if (retry) TradingFailureKind.NO_LONGER_AVAILABLE else TradingFailureKind.INPUT_REJECTED)
            if (matches.size != 1 || source.count { it.role == role } != 1)
                throw TradingFailure("ARTIFACT_ROLE_CONFLICT", TradingFailureKind.CONFLICT)
            val artifact = matches.single()
            if (artifact.dialectCandidate != "AUTO" && artifact.dialectCandidate != when (role) {
                    TradingArtifactRole.TRADE_HISTORY -> "BINANCE_USDS_TRADE_HISTORY_V1"
                    TradingArtifactRole.POSITION_HISTORY -> "BINANCE_USDS_POSITION_HISTORY_V1"
                }) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INPUT_REJECTED)
            if (!artifact.rawReparseAvailable(now) || artifact.byteSize <= 0 ||
                !Regex("[0-9a-f]{64}").matches(artifact.sha256))
                throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", TradingFailureKind.NO_LONGER_AVAILABLE)
            NormalizationArtifactInput(artifact.id, role, artifact.sha256, artifact.byteSize,
                artifact.contentType, artifact.dialectCandidate)
        }.sortedBy { it.role.ordinal }
        val policy = settings.retryPolicy()
        val deliveryPolicy = settings.resultDeliveryPolicy()
        val policyJson = mapper.writeValueAsString(mapOf(
            "policyVersion" to policy.version, "maxAttempts" to policy.maxAttempts,
            "maxElapsedTime" to policy.maxElapsedTime.toString(),
            "initialDelay" to policy.initialDelay.toString(),
            "maxDelay" to policy.maxDelay.toString(), "jitter" to policy.jitter))
        val terms = if (retry) session.normalizationRun?.input?.instrumentTermsJson
            ?: throw TradingFailure("RESOURCE_STATE_CONFLICT", TradingFailureKind.CONFLICT)
        else canonical(settings.instrumentTermsJson())
        val input = NormalizationInput(book.id, session.id, owner.value, prepared, book.venue.name,
            book.productFamily.name, book.positionMode.name, book.settlementAsset.name,
            session.sourceTimezone, session.reviewTimezone, session.versionSetJson, terms,
            session.dataPolicyVersion, policy.version, policyJson, session.deletionGeneration)
        val identity = try {
            NormalizationInputIdentity.fromPinnedInput(input, mapper.readValue(pinnedVersions, Map::class.java))
        } catch (_: Exception) { throw TradingFailure("VERSION_UNAVAILABLE", TradingFailureKind.INPUT_REJECTED) }
        val hash = NormalizationInputCanonicalizer.hash(identity)
        val previous = if (retry) {
            val previous = session.normalizationRun
                ?: throw TradingFailure("RESOURCE_STATE_CONFLICT", TradingFailureKind.CONFLICT)
            val previousIdentity = previous.inputIdentity
                ?: throw TradingFailure("VERSION_UNAVAILABLE", TradingFailureKind.INPUT_REJECTED)
            if (previous.inputFingerprint != hash ||
                !NormalizationInputCanonicalizer.canonicalize(previousIdentity).contentEquals(
                    NormalizationInputCanonicalizer.canonicalize(identity)) ||
                previous.input.artifacts != prepared ||
                previous.input.deletionGeneration != session.deletionGeneration)
                throw TradingFailure("ARTIFACT_IDENTITY_CONFLICT", TradingFailureKind.CONFLICT)
            previous
        } else null
        val runId = identifiers.runId()
        val dispatchId = identifiers.dispatchId()
        val attemptToken = identifiers.attemptToken()
        val resultPin = previous?.resultVersionPin ?: NormalizationResultVersionPin.V1
        val logicalKey = NormalizationInputFingerprint.logicalKey(owner.value, hash,
            input.deletionGeneration, resultPin, mapper)
        val admitted = session.admitNormalization(runId, input, logicalKey, attemptToken,
            identity, mapper.readValue(session.versionSetJson, Map::class.java), deliveryPolicy, resultPin, now)
        val dispatch = NormalizationDispatch(dispatchId, runId.value,
            NormalizationInputFingerprint.dispatchKey(runId.value, logicalKey, attemptToken,
                input.versionSetJson, input.deletionGeneration, mapper),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now,
            resultVersionPin = resultPin)
        return NormalizationAdmission(session, admitted, dispatch)
    }

    fun canonical(raw: String): String = try {
        NormalizationInputFingerprint.canonical(strictMapper.readValue(raw, Any::class.java), mapper)
    } catch (_: Exception) { throw TradingFailure("VERSION_UNAVAILABLE", TradingFailureKind.INPUT_REJECTED) }
}
