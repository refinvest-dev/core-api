package com.refinvest.core.tradingreview.application

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

@Service
open class TradingNormalizationService(
    private val members: TradingMemberProvider,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val sessions: TradingImportSessionStore,
    private val artifacts: TradingSourceArtifactStore,
    private val idempotency: TradingIdempotencyStore,
    private val runs: TradingNormalizationStore,
    private val versions: TradingVersionPins,
    private val settings: TradingNormalizationSettings,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : TradingNormalizationUseCase {
    @Transactional
    override fun validate(id: TradingImportSessionId, targetVersionSetJson: String?, key: String): TradingImportSession =
        admit(id, targetVersionSetJson, key, false)

    @Transactional
    override fun retry(id: TradingImportSessionId, key: String): TradingImportSession =
        admit(id, null, key, true)

    private fun admit(id: TradingImportSessionId, requestedVersions: String?, key: String,
                      retry: Boolean): TradingImportSession {
        if (key.length !in 1..128 || !Regex("[A-Za-z0-9._~-]+").matches(key))
            throw TradingFailure("IDEMPOTENCY_KEY_INVALID", 400)
        val owner = members.currentMemberId()
        val initial = sessions.find(id, owner) ?: throw TradingFailure(
            if (sessions.deleted(id, owner)) "RESOURCE_DELETED" else "RESOURCE_NOT_FOUND",
            if (sessions.deleted(id, owner)) 410 else 404)
        val initialBook = books.find(initial.bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val account = accounts.find(initialBook.accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val book = books.find(initialBook.id, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val session = sessions.find(id, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE ||
            session.deletionPending) throw TradingFailure("RESOURCE_DELETED", 410)
        if (session.bookId != book.id || session.owner != book.owner || session.owner != owner)
            throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        if (session.declaredPositionMode != book.positionMode ||
            session.sourceTimezone.isBlank() || session.reviewTimezone.isBlank())
            throw TradingFailure("REQUEST_INVALID", 422)
        val operation = if (retry) "retryTradingImportSession" else "validateTradingImportSession"
        val pinnedVersions = canonical(session.versionSetJson)
        if (requestedVersions != null && canonical(requestedVersions) != pinnedVersions)
            throw TradingFailure("VERSION_UNAVAILABLE", 422)
        val publicPayload = NormalizationInputFingerprint.hashParts(id.value.toString(),
            requestedVersions?.let(::canonical) ?: "", if (retry) "retry" else "validate")
        when (idempotency.claim(owner, operation, key, publicPayload)) {
            is IdempotencyClaim.Replayed -> return sessions.find(id, owner)
                ?: throw TradingFailure("RESOURCE_DELETED", 410)
            IdempotencyClaim.New -> Unit
        }
        if (!retry && session.status == TradingImportStatus.VALIDATING) {
            if (runs.findActive(id)?.id != session.activeNormalizationRunId)
                throw TradingFailure("RESOURCE_STATE_CONFLICT", 409)
            idempotency.complete(owner, operation, key, id.value)
            return session
        }
        if (retry && (session.status != TradingImportStatus.PROCESSING_FAILED ||
                session.failure?.retryable != true))
            throw TradingFailure("RESOURCE_STATE_CONFLICT", 409)
        if (!retry && session.status != TradingImportStatus.RECEIVED)
            throw TradingFailure("RESOURCE_STATE_CONFLICT", 409)
        // The version pin must still be executable. Retry never silently moves to a newer version.
        if (canonical(versions.currentJson()) != pinnedVersions) throw TradingFailure("VERSION_UNAVAILABLE", 422)
        val now = clock.instant()
        val source = artifacts.list(id)
        val prepared = TradingArtifactRole.entries.map { role ->
            val matches = source.filter { it.role == role && it.retentionStatus == TradingArtifactRetentionStatus.ACTIVE }
            if (matches.isEmpty()) throw TradingFailure(
                if (retry) "SOURCE_ARTIFACT_EXPIRED" else "MISSING_REQUIRED_ARTIFACT",
                if (retry) 410 else 422)
            if (matches.size != 1 || source.count { it.role == role } != 1)
                throw TradingFailure("ARTIFACT_ROLE_CONFLICT", 409)
            val artifact = matches.single()
            if (artifact.dialectCandidate != "AUTO" && artifact.dialectCandidate != when (role) {
                    TradingArtifactRole.TRADE_HISTORY -> "BINANCE_USDS_TRADE_HISTORY_V1"
                    TradingArtifactRole.POSITION_HISTORY -> "BINANCE_USDS_POSITION_HISTORY_V1"
                }) throw TradingFailure("REQUEST_INVALID", 422)
            if (!artifact.rawReparseAvailable(now) || artifact.byteSize <= 0 ||
                !Regex("[0-9a-f]{64}").matches(artifact.sha256))
                throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", 410)
            NormalizationArtifactInput(artifact.id, role, artifact.sha256, artifact.byteSize,
                artifact.contentType, artifact.dialectCandidate)
        }.sortedBy { it.role.ordinal }
        val policy = settings.retryPolicy()
        val policyJson = mapper.writeValueAsString(mapOf(
            "policyVersion" to policy.version, "maxAttempts" to policy.maxAttempts,
            "maxElapsedTime" to policy.maxElapsedTime.toString(),
            "initialDelay" to policy.initialDelay.toString(),
            "maxDelay" to policy.maxDelay.toString(), "jitter" to policy.jitter))
        val terms = canonical(settings.instrumentTermsJson())
        val input = NormalizationInput(book.id, session.id, owner.value, prepared, book.venue.name,
            book.productFamily.name, book.positionMode.name, book.settlementAsset.name,
            session.sourceTimezone, session.reviewTimezone, pinnedVersions, terms,
            session.dataPolicyVersion, policy.version, policyJson, session.deletionGeneration)
        val hash = NormalizationInputFingerprint.hash(input, mapper)
        val previous = if (retry) {
            val previous = session.activeNormalizationRunId?.let(runs::findRun)
                ?: throw TradingFailure("RESOURCE_STATE_CONFLICT", 409)
            if (previous.inputFingerprint != hash || previous.input.artifacts != prepared ||
                previous.input.deletionGeneration != session.deletionGeneration)
                throw TradingFailure("ARTIFACT_IDENTITY_CONFLICT", 409)
            previous
        } else null
        val runId = UUID.randomUUID().toString()
        val dispatchId = UUID.randomUUID().toString()
        val attemptToken = UUID.randomUUID().toString()
        val logicalKey = NormalizationInputFingerprint.logicalKey(input, mapper)
        val run = NormalizationRun(runId, input, hash, logicalKey,
            NormalizationRunStatus.DISPATCH_PENDING, 1, attemptToken, null, null,
            now, now, null, now, null, 0, previous?.id)
        val dispatch = NormalizationDispatch(dispatchId, runId,
            NormalizationInputFingerprint.dispatchKey(runId, logicalKey, attemptToken,
                input.versionSetJson, input.deletionGeneration, mapper),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        runs.insert(run, dispatch)
        if (!sessions.update(session.beginValidation(runId, now))) throw TradingFailure("CONCURRENT_MODIFICATION", 409)
        runs.audit(runId, if (retry) "EXPLICIT_RETRY" else "VALIDATION_ADMITTED", null, now)
        idempotency.complete(owner, operation, key, id.value)
        return session.beginValidation(runId, now)
    }

    private fun canonical(raw: String): String = try {
        NormalizationInputFingerprint.canonical(mapper.readValue(raw, Any::class.java), mapper)
    } catch (_: Exception) { throw TradingFailure("VERSION_UNAVAILABLE", 422) }
}

object NormalizationInputFingerprint {
    fun hash(input: NormalizationInput, mapper: ObjectMapper): String {
        // ADR-065: sorted JSON keys, sorted artifact roles, UTF-8, exact string values.
        // Runtime IDs, generation, locators, expiry, retry policy, and attempt token are absent.
        val body = mapOf(
            "tradingBookId" to input.tradingBookId.value.toString(),
            "sourceImportSessionIds" to listOf(input.importSessionId.value.toString()),
            "artifactsByRole" to input.artifacts.sortedWith(compareBy({ it.role.ordinal }, { it.artifactId.value }))
                .map { mapOf("role" to it.role.name, "artifactId" to it.artifactId.value.toString(),
                    "rawSha256" to it.sha256) },
            "productDeclaration" to mapOf("venue" to input.venue, "productFamily" to input.productFamily,
                "settlementAsset" to input.settlementAsset),
            "declaredPositionMode" to input.positionMode,
            "sourceTimezone" to input.sourceTimezone,
            "reviewTimezone" to input.reviewTimezone,
            "targetVersionSet" to mapper.readValue(input.versionSetJson, Any::class.java),
        )
        return sha256(canonical(body, mapper))
    }

    fun canonical(value: Any?, mapper: ObjectMapper): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.sortedWith { left, right ->
            compareCodePoints(left.key.toString(), right.key.toString())
        }.joinToString(",", "{", "}") {
            mapper.writeValueAsString(it.key.toString()) + ":" + canonical(it.value, mapper)
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { canonical(it, mapper) }
        else -> mapper.writeValueAsString(value)
    }

    fun logicalKey(input: NormalizationInput, mapper: ObjectMapper): String {
        val versions = mapper.readValue(input.versionSetJson, Map::class.java)
        val body = mapOf(
            "ownerScope" to input.ownerId.toString(),
            "importSessionId" to input.importSessionId.value.toString(),
            "artifactsByRole" to input.artifacts.sortedBy { it.role.ordinal }.map {
                mapOf("role" to it.role.name, "rawSha256" to it.sha256)
            },
            "normalizationReconstructionVersionSetHash" to versions["versionSetHash"],
            "dataPolicyVersion" to input.dataPolicyVersion,
            "deletionGeneration" to input.deletionGeneration,
        )
        return sha256(canonical(body, mapper))
    }

    fun dispatchKey(runId: String, logicalKey: String, initialAttemptToken: String,
                    versionSetJson: String, deletionGeneration: Long, mapper: ObjectMapper): String {
        val versionHash = mapper.readTree(versionSetJson).path("versionSetHash").asText()
        return sha256(canonical(mapOf(
            "operationKind" to "NORMALIZATION",
            "coreLogicalAggregateId" to runId,
            "logicalRequestKey" to logicalKey,
            "initialAttemptToken" to initialAttemptToken,
            "targetVersionSetHash" to versionHash,
            "deletionGeneration" to deletionGeneration,
        ), mapper))
    }

    fun hashParts(vararg parts: String): String = sha256(parts.joinToString("") {
        val bytes = it.toByteArray(StandardCharsets.UTF_8)
        bytes.size.toString() + ":" + it
    })

    private fun compareCodePoints(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left.codePointAt(i)
            val b = right.codePointAt(j)
            if (a != b) return a.compareTo(b)
            i += Character.charCount(a)
            j += Character.charCount(b)
        }
        return (left.length - i).compareTo(right.length - j)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
