package com.refinvest.core.tradingreview.application

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportFailure
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.book.TradingBookStore
import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.min
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

@Service
class TradingNormalizationResultProcessor(
    private val runs: TradingNormalizationStore,
    private val sessions: TradingImportSessionStore,
    private val books: TradingBookStore,
    private val results: TradingNormalizationResultStore,
    private val compute: TradingNormalizationResultClient,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val mapper: ObjectMapper,
    private val jitter: TradingRetryJitter,
    private val transition: TradingNormalizationTransitionGate,
    @Value("\${refinvest.trading-review.normalization.delivery.parser-max-depth:64}")
    parserMaxDepth: Int = 64,
    @Value("\${refinvest.trading-review.normalization.delivery.parser-max-string-length:1048576}")
    parserMaxStringLength: Int = 1_048_576,
    @Value("\${refinvest.trading-review.normalization.delivery.parser-max-name-length:256}")
    parserMaxNameLength: Int = 256,
    @Value("\${refinvest.trading-review.normalization.delivery.parser-max-token-count:5000000}")
    parserMaxTokenCount: Long = 5_000_000,
    @Value("\${refinvest.trading-review.normalization.delivery.stage-batch-size:100}")
    private val stageBatchSize: Int = 100,
) {
    init { require(stageBatchSize > 0) }
    private val strictResultMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    private val streamingReader = NormalizationResultStreamingReader(strictResultMapper,
        NormalizationResultReadLimits(parserMaxDepth, parserMaxStringLength,
            parserMaxNameLength, parserMaxTokenCount))
    private val stagedVerifier = NormalizationStagedResultVerifier(results, strictResultMapper,
        stageBatchSize)
    fun processOne(): Boolean {
        val worker = UUID.randomUUID().toString()
        val dispatch = transactions.execute {
            results.claimReady(clock.instant(), Duration.ofMinutes(15), worker)
        } ?: return false
        val run = runs.findRun(dispatch.runId) ?: return true
        val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId))
        if (session == null || !isCurrent(run, session)) {
            obsolete(run, dispatch, worker)
            return true
        }
        val jobId = run.computeJobId ?: run {
            terminal(run, dispatch, worker, "RESULT_SCHEMA_UNSUPPORTED")
            return true
        }
        val savedBase = results.loadStageBase(run.id)
        val currentStageFormat = savedBase?.let {
            strictResultMapper.readTree(it).path(NormalizationResultStreamingReader.STAGE_FORMAT_FIELD)
                .asText() == NormalizationResultStreamingReader.STREAM_STAGE_FORMAT
        } == true
        val locallyStaged = results.loadStageDescriptor(run, dispatch)
            ?.takeIf { currentStageFormat && stageComplete(run.id, it) }
        val descriptor = locallyStaged ?: when (val lookup = compute.descriptor(jobId)) {
            is NormalizationResultLookup.Found -> lookup.descriptor
            is NormalizationResultLookup.Retry -> {
                retry(run, dispatch, worker, lookup.code)
                return true
            }
            is NormalizationResultLookup.Rejected -> {
                terminal(run, dispatch, worker,
                    lookup.code.takeIf { it == "RESULT_NOT_AVAILABLE" } ?: "RESULT_SCHEMA_UNSUPPORTED")
                return true
            }
        }
        try {
            validateDescriptor(run, dispatch, descriptor, locallyStaged != null)
        } catch (error: ResultIntegrityFailure) {
            terminal(run, dispatch, worker, error.code)
            return true
        }
        // Pre-streaming stages did not preserve bounded collection rows. Re-fetch the
        // authenticated V1 resource and rebuild rather than guessing missing data.
        if (savedBase != null && !currentStageFormat) {
            val cleared = transactions.execute {
                if (!results.hasReadyLease(dispatch.id, worker, clock.instant())) false
                else { results.discardStage(run.id); true }
            } == true
            if (!cleared) return true
        }
        var verified: VerifiedStagedResult? = null
        var failure: String? = null
        if (currentStageFormat && savedBase != null && stageComplete(run.id, descriptor)) {
            try {
                @Suppress("UNCHECKED_CAST")
                val base = strictResultMapper.readValue(savedBase, Map::class.java) as Map<String, Any?>
                verified = stagedVerifier.verify(base, run, dispatch.id)
                comparePayloadDescriptor(requireNotNull(verified), descriptor)
            } catch (error: ResultIntegrityFailure) { failure = error.code }
            catch (_: Exception) { failure = "RESULT_SCHEMA_UNSUPPORTED" }
        }
        repeat(2) { transportAttempt ->
            if (verified != null || failure != null) return@repeat
            when (val downloaded = compute.download(descriptor,
                requireNotNull(run.resultDeliveryPolicy))) {
                is NormalizationPayloadLookup.Found -> downloaded.payload.use { payload ->
                    if (payload.byteSize != descriptor.byteSize) {
                        if (transportAttempt == 1) failure = "RESULT_PAYLOAD_LENGTH_MISMATCH"
                    } else if (payload.sha256 != descriptor.payloadSha256) {
                        if (transportAttempt == 1) failure = "RESULT_TRANSPORT_HASH_MISMATCH"
                    } else {
                        val currentSession = sessions.find(run.input.importSessionId,
                            MemberId(run.input.ownerId))
                        if (currentSession == null || !isCurrent(run, currentSession)) {
                            obsolete(run, dispatch, worker, descriptor)
                            return true
                        }
                        try {
                            val stageAllowed = transactions.execute {
                                if (!results.hasReadyLease(dispatch.id, worker, clock.instant())) false
                                else { results.beginStage(run, descriptor, clock.instant()); true }
                            } == true
                            if (!stageAllowed) return true
                            val declaredLimits = declaredItemLimits(descriptor)
                            val batch = ArrayList<NormalizationStagedItem>(stageBatchSize)
                            val receivedCounts = mutableMapOf<String, Long>()
                            fun flush() {
                                if (batch.isEmpty()) return
                                transactions.execute {
                                    if (!results.hasReadyLease(dispatch.id, worker, clock.instant()))
                                        throw LeaseLost()
                                    results.stageItems(run.id, batch)
                                }
                                batch.clear()
                            }
                            val base = payload.open().use { input ->
                                streamingReader.read(input) { section, id, value ->
                                    val nextCount = (receivedCounts[section] ?: 0L) + 1
                                    if (nextCount > (declaredLimits[section]
                                            ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")))
                                        throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
                                    receivedCounts[section] = nextCount
                                    batch.add(staged(section, id, value))
                                    if (batch.size >= stageBatchSize) flush()
                                }
                            }
                            if (base["resultSchemaVersion"] != run.resultVersionPin.schemaVersion ||
                                base["resultHashSpecificationVersion"] !=
                                    run.resultVersionPin.hashSpecificationVersion)
                                throw ResultIntegrityFailure("RESULT_VERSION_PIN_MISMATCH")
                            flush()
                            transactions.execute {
                                if (!results.hasReadyLease(dispatch.id, worker, clock.instant()))
                                    throw LeaseLost()
                                results.saveStageBase(run.id, mapper.writeValueAsString(base))
                            }
                            val result = stagedVerifier.verify(base, run, dispatch.id)
                            comparePayloadDescriptor(result, descriptor)
                            for (digest in result.sectionDigests) {
                                val section = digest.getValue("sectionType") as String
                                val declared = (digest.getValue("itemCount") as Number).toLong()
                                if ((receivedCounts[section] ?: 0L) != declared)
                                    throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
                            }
                            if ((receivedCounts["VALIDATION_ISSUES"] ?: 0L) !=
                                result.counts["validationIssueCount"])
                                throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
                            verified = result
                        } catch (_: LeaseLost) { return true }
                        catch (error: ResultIntegrityFailure) { failure = error.code }
                        catch (error: NormalizationResultStoreFailure) { failure = error.safeCode }
                        catch (_: Exception) { failure = "RESULT_PAYLOAD_PARSE_FAILED" }
                    }
                }
                is NormalizationPayloadLookup.Retry -> failure = downloaded.code
                is NormalizationPayloadLookup.Rejected -> {
                    if (downloaded.code != "RESULT_PAYLOAD_LENGTH_MISMATCH" || transportAttempt == 1)
                        failure = downloaded.code
                }
            }
        }
        if (failure != null) {
            if (failure in setOf("TEMPORARY_DEPENDENCY_FAILURE", "RESULT_PAYLOAD_STREAM_ABORTED"))
                retry(run, dispatch, worker, failure!!)
            else terminal(run, dispatch, worker, failure!!)
            return true
        }
        if (verified == null) return true
        try {
            val persistedBaseJson = results.loadStageBase(run.id)
                ?: throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
            @Suppress("UNCHECKED_CAST")
            val persistedBase = strictResultMapper.readValue(persistedBaseJson, Map::class.java)
                as Map<String, Any?>
            val independentlyVerified = stagedVerifier.verify(persistedBase, run, dispatch.id)
            comparePayloadDescriptor(independentlyVerified, descriptor)
            var transitionHeld = false
            val published = transactions.execute {
                if (!transition.publicationAllowed()) {
                    transitionHeld = true
                    results.retryReady(dispatch.id, worker, "NORMALIZATION_TRANSITION_PAUSED",
                        clock.instant().plusSeconds(5), clock.instant())
                    return@execute false
                }
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked == null || !isCurrent(run, locked)) return@execute false
                if (!results.hasReadyLease(dispatch.id, worker, clock.instant())) return@execute false
                results.markValidated(run.id, clock.instant())
                results.publish(run, dispatch, worker, descriptor,
                    mapper.writeValueAsString(independentlyVerified.manifestBase),
                    mapper.writeValueAsString(independentlyVerified.coverages),
                    mapper.writeValueAsString(independentlyVerified.capabilities),
                    mapper.writeValueAsString(independentlyVerified.qualityBase),
                    independentlyVerified.ledgerContentHash, clock.instant())
            } == true
            if (!transitionHeld && !published && transactions.execute {
                    results.hasReadyLease(dispatch.id, worker, clock.instant()) } == true)
                obsolete(run, dispatch, worker, descriptor)
        } catch (_: LeaseLost) { return true }
        catch (error: ResultIntegrityFailure) { terminal(run, dispatch, worker, error.code) }
        catch (error: NormalizationResultStoreFailure) { terminal(run, dispatch, worker, error.safeCode) }
        catch (_: Exception) { retry(run, dispatch, worker, "TEMPORARY_DEPENDENCY_FAILURE") }
        return true
    }

    fun acknowledgeOne(): Boolean {
        val worker = UUID.randomUUID().toString()
        val ack = transactions.execute { results.claimAck(clock.instant(), Duration.ofSeconds(45), worker) }
            ?: return false
        val outcome = compute.acknowledge(ack)
        transactions.execute {
            val now = clock.instant()
            when (outcome) {
                NormalizationAckOutcome.Accepted -> results.completeAck(ack.idempotencyKey, worker, now)
                is NormalizationAckOutcome.Rejected -> results.failAck(ack.idempotencyKey, worker,
                    outcome.code, now)
                NormalizationAckOutcome.Retry -> {
                val pinned = mapper.readTree(ack.retryPolicyJson)
                val attempts = pinned.path("maxAttempts").asInt()
                val deadline = minOf(ack.payloadExpiresAt,
                    ack.occurredAt.plus(Duration.parse(pinned.path("maxElapsedTime").asText())))
                if (ack.attemptCount >= attempts || !now.isBefore(deadline)) {
                    results.failAck(ack.idempotencyKey, worker, "RESULT_NOT_AVAILABLE", now)
                    return@execute
                }
                val initial = Duration.parse(pinned.path("initialDelay").asText())
                val maximum = Duration.parse(pinned.path("maxDelay").asText())
                val scaled = min(initial.toMillis().toDouble() *
                    (1L shl min(ack.attemptCount - 1, 20)), maximum.toMillis().toDouble())
                val factor = if (pinned.path("jitter").asText() == "FULL_JITTER") jitter.factor() else 1.0
                val next = now.plusMillis((scaled * factor).toLong().coerceAtLeast(0))
                if (!next.isBefore(deadline))
                    results.failAck(ack.idempotencyKey, worker, "RESULT_NOT_AVAILABLE", now)
                else results.retryAck(ack.idempotencyKey, worker, next, now)
                }
            }
        }
        return true
    }

    fun cleanupObsoleteStaging(): Int = transactions.execute {
        results.cleanupObsoleteStages(100) + results.cleanupAcknowledgementsForDeletedSessions(100)
    } ?: 0

    private fun validateDescriptor(run: NormalizationRun, dispatch: NormalizationDispatch,
                                   descriptor: NormalizationResultDescriptor,
                                   locallyComplete: Boolean = false) {
        val identity = run.inputIdentity ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        val delivery = run.resultDeliveryPolicy ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        val supportedPair = (descriptor.resultSchemaVersion == NormalizationResultVerifier.RESULT_SCHEMA_V1 &&
            descriptor.resultHashSpecificationVersion == NormalizationResultVerifier.RESULT_HASH_V1) ||
            (descriptor.resultSchemaVersion == NormalizationResultVerifier.RESULT_SCHEMA_V2 &&
                descriptor.resultHashSpecificationVersion == NormalizationResultVerifier.RESULT_HASH_V2)
        if (!supportedPair ||
            descriptor.inputHashSpecificationVersion != identity.hashSpecificationVersion)
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (dispatch.resultVersionPin != run.resultVersionPin ||
            descriptor.resultSchemaVersion != run.resultVersionPin.schemaVersion ||
            descriptor.resultHashSpecificationVersion != run.resultVersionPin.hashSpecificationVersion)
            throw ResultIntegrityFailure("RESULT_VERSION_PIN_MISMATCH")
        if (descriptor.jobId != run.computeJobId || descriptor.logicalJobId != dispatch.id ||
            descriptor.runId != run.id || descriptor.attemptToken != run.attemptToken ||
            descriptor.deletionGeneration != run.input.deletionGeneration ||
            descriptor.inputSetHash != run.inputFingerprint ||
            !same(identity.targetVersionSet.toWireValue(), descriptor.targetVersionSet))
            throw ResultIntegrityFailure("INPUT_SET_HASH_MISMATCH")
        if (descriptor.resultDeliveryPolicyVersion != delivery.policyVersion)
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (descriptor.contentEncoding !in delivery.acceptedContentEncodings)
            throw ResultIntegrityFailure("RESULT_CONTENT_ENCODING_UNSUPPORTED")
        if (descriptor.byteSize > delivery.maxAcceptedPayloadBytes)
            throw ResultIntegrityFailure("RESULT_PAYLOAD_LIMIT_EXCEEDED")
        if (descriptor.byteSize < 1 || !Regex("[0-9a-f]{64}").matches(descriptor.payloadSha256))
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (!locallyComplete && !descriptor.expiresAt.isAfter(clock.instant()))
            throw ResultIntegrityFailure("RESULT_NOT_AVAILABLE")
    }

    private fun comparePayloadDescriptor(result: VerifiedStagedResult,
                                         descriptor: NormalizationResultDescriptor) {
        if (result.manifestHash != descriptor.reconciliationManifestHash)
            throw ResultIntegrityFailure("RECONCILIATION_MANIFEST_HASH_MISMATCH")
        if (result.resultHash != descriptor.resultHash)
            throw ResultIntegrityFailure("RESULT_HASH_MISMATCH")
        if (!same(result.counts, descriptor.counts)) throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
        if (!same(result.sectionDigests, descriptor.sectionDigests))
            throw ResultIntegrityFailure("RESULT_SECTION_HASH_MISMATCH")
    }

    private fun staged(section: String, id: String, value: Any?): NormalizationStagedItem {
        val normalized = stagedVerifier.normalizeForStage(section, value)
        return NormalizationStagedItem(section, id, NormalizationResultCanonicalizer.hash(normalized),
            mapper.writeValueAsString(normalized))
    }

    private fun declaredItemLimits(descriptor: NormalizationResultDescriptor): Map<String, Long> {
        val limits = mutableMapOf<String, Long>()
        for (raw in descriptor.sectionDigests) {
            val digest = raw as? Map<*, *> ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
            val section = digest["sectionType"] as? String
                ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
            val count = (digest["itemCount"] as? Number)?.toString()?.toLongOrNull()
                ?.takeIf { it in 0..9_007_199_254_740_991L }
                ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
            if (limits.putIfAbsent(section, count) != null)
                throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        }
        val issues = (descriptor.counts["validationIssueCount"] as? Number)?.toString()?.toLongOrNull()
            ?.takeIf { it in 0..9_007_199_254_740_991L }
            ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        limits["VALIDATION_ISSUES"] = issues
        if (descriptor.resultSchemaVersion == NormalizationResultVerifier.RESULT_SCHEMA_V2) {
            val units = (descriptor.counts["reviewUnitCount"] as? Number)?.toString()?.toLongOrNull()
                ?.takeIf { it in 0..9_007_199_254_740_991L }
                ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
            limits["ANALYTICS_INPUT_SUPPLEMENT_ROWS"] = units
        }
        return limits
    }

    private fun stageComplete(runId: String, descriptor: NormalizationResultDescriptor): Boolean {
        val actual = results.stagedCounts(runId)
        for (raw in descriptor.sectionDigests) {
            val digest = raw as? Map<*, *> ?: return false
            val section = digest["sectionType"] as? String ?: return false
            val count = (digest["itemCount"] as? Number)?.toLong() ?: return false
            if ((actual[section] ?: 0L) != count) return false
        }
        val nested = mapOf("positionAllocationCount" to "POSITION_ALLOCATIONS",
            "excludedReviewUnitCount" to "EXCLUDED_REVIEW_UNITS",
            "validationIssueCount" to "VALIDATION_ISSUES")
        return nested.all { (field, section) -> actual[section] ==
            (descriptor.counts[field] as? Number)?.toLong() } &&
            (descriptor.resultSchemaVersion != NormalizationResultVerifier.RESULT_SCHEMA_V2 ||
                (actual["ANALYTICS_INPUT_SUPPLEMENT_ROWS"] ?: 0L) ==
                    (descriptor.counts["reviewUnitCount"] as? Number)?.toLong())
    }

    private fun retry(run: NormalizationRun, dispatch: NormalizationDispatch, worker: String, code: String) {
        var stale = false
        transactions.execute {
            val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
            val now = clock.instant()
            if (locked == null || !isCurrent(run, locked)) {
                stale = true
                return@execute
            }
            val policy = mapper.readTree(run.input.retryPolicyJson)
            val attempts = policy.path("maxAttempts").asInt()
            val elapsed = Duration.parse(policy.path("maxElapsedTime").asText())
            if (dispatch.attemptCount >= attempts || Duration.between(run.createdAt, now) >= elapsed) {
                failLocked(run, dispatch, locked, worker, code, now)
                return@execute
            }
            val initial = Duration.parse(policy.path("initialDelay").asText()).toMillis()
            val maximum = Duration.parse(policy.path("maxDelay").asText()).toMillis()
            val capped = min(initial.toDouble() * (1L shl min(dispatch.attemptCount - 1, 20)), maximum.toDouble())
            val delay = if (policy.path("jitter").asText() == "FULL_JITTER") capped * jitter.factor() else capped
            val next = now.plusMillis(delay.toLong().coerceAtLeast(0))
            if (!next.isBefore(run.createdAt.plus(elapsed))) failLocked(run, dispatch, locked, worker, code, now)
            else results.retryReady(dispatch.id, worker, code, next, now)
        }
        if (stale) obsolete(run, dispatch, worker)
    }

    private fun terminal(run: NormalizationRun, dispatch: NormalizationDispatch, worker: String, code: String) {
        var stale = false
        transactions.execute {
            val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
            val now = clock.instant()
            if (locked == null || !isCurrent(run, locked)) {
                stale = true
                return@execute
            }
            failLocked(run, dispatch, locked, worker, code, now)
        }
        if (stale) obsolete(run, dispatch, worker)
    }

    private fun failLocked(run: NormalizationRun, dispatch: NormalizationDispatch,
                           session: TradingImportSession, worker: String, code: String, now: Instant) {
        if (!runs.markTerminal(dispatch.id, worker, code, now)) return
        results.discardStage(run.id)
        val failure = TradingImportFailure(code, false, "WAIT_FOR_RECOVERY", now)
        val failed = session.failValidation(run.id, run.input.deletionGeneration, failure, now) ?: return
        check(sessions.update(failed))
        runs.audit(run.id, "PROCESSING_FAILED", code, now)
    }

    private fun isCurrent(run: NormalizationRun, session: TradingImportSession): Boolean =
        !session.deletionPending && session.status == TradingImportStatus.VALIDATING &&
            session.activeNormalizationRunId == run.id &&
            session.deletionGeneration == run.input.deletionGeneration &&
            books.find(run.input.tradingBookId, MemberId(run.input.ownerId))?.status == TradingLifecycleStatus.ACTIVE

    private fun obsolete(run: NormalizationRun, dispatch: NormalizationDispatch, worker: String,
                         knownDescriptor: NormalizationResultDescriptor? = null) {
        val jobId = run.computeJobId
        val lookup = if (knownDescriptor == null && jobId != null)
            compute.descriptor(jobId) else null
        if (lookup is NormalizationResultLookup.Retry) {
            val now = clock.instant()
            val policy = mapper.readTree(run.input.retryPolicyJson)
            val limit = policy.path("maxAttempts").asInt()
            val elapsed = Duration.parse(policy.path("maxElapsedTime").asText())
            if (dispatch.attemptCount < limit && now.isBefore(run.createdAt.plus(elapsed))) {
                val initial = Duration.parse(policy.path("initialDelay").asText()).toMillis().toDouble()
                val maximum = Duration.parse(policy.path("maxDelay").asText()).toMillis().toDouble()
                val delay = min(initial * (1L shl min(dispatch.attemptCount - 1, 20)), maximum)
                val adjusted = if (policy.path("jitter").asText() == "FULL_JITTER")
                    delay * jitter.factor() else delay
                val next = now.plusMillis(adjusted.toLong().coerceAtLeast(0))
                if (next.isBefore(run.createdAt.plus(elapsed))) {
                    transactions.execute {
                        results.retryReady(dispatch.id, worker, "TEMPORARY_DEPENDENCY_FAILURE", next, now)
                    }
                    return
                }
            }
        }
        val descriptor = knownDescriptor ?: (lookup as? NormalizationResultLookup.Found)?.descriptor
        val verifiedDescriptor = descriptor?.takeIf {
            try { validateDescriptor(run, dispatch, it); true }
            catch (_: ResultIntegrityFailure) { false }
        }
        transactions.execute {
            val now = clock.instant()
            if (verifiedDescriptor == null ||
                !results.discardStale(run, dispatch, worker, verifiedDescriptor, now)) {
                if (runs.markObsolete(dispatch.id, worker, now)) results.discardStage(run.id)
            }
        }
    }

    private fun same(a: Any?, b: Any?): Boolean =
        NormalizationResultCanonicalizer.canonicalize(a).contentEquals(
            NormalizationResultCanonicalizer.canonicalize(b))

    private class LeaseLost : RuntimeException()
}
