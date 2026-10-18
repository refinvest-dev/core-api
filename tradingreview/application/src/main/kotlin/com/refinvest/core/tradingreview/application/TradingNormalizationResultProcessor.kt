package com.refinvest.core.tradingreview.application

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.core.StreamReadFeature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.min

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
) {
    private val strictResultMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
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
        val locallyStaged = results.loadStageDescriptor(run, dispatch)
            ?.takeIf { results.loadStageBase(run.id) != null && stageComplete(run.id, it) }
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
        val savedBase = try {
            val stageAllowed = transactions.execute {
                if (!results.hasReadyLease(dispatch.id, worker, clock.instant())) false
                else { results.beginStage(run, descriptor, clock.instant()); true }
            } == true
            if (!stageAllowed) return true
            results.loadStageBase(run.id)
        } catch (error: NormalizationResultStoreFailure) {
            terminal(run, dispatch, worker, error.safeCode)
            return true
        }
        var verified: VerifiedNormalizationResult? = null
        var failure: String? = null
        var recovered = false
        if (savedBase != null && stageComplete(run.id, descriptor)) {
            try {
                @Suppress("UNCHECKED_CAST")
                val base = strictResultMapper.readValue(savedBase, Map::class.java) as Map<String, Any?>
                val restored = rehydrate(base, results.loadItems(run.id))
                verified = NormalizationResultVerifier.verify(restored, run, dispatch.id)
                comparePayloadDescriptor(verified!!, descriptor)
                recovered = true
            } catch (error: ResultIntegrityFailure) { failure = error.code }
            catch (_: Exception) { failure = "RESULT_SCHEMA_UNSUPPORTED" }
        }
        repeat(2) { transportAttempt ->
            if (verified != null || failure != null) return@repeat
            when (val downloaded = compute.download(descriptor.payloadId)) {
                is NormalizationPayloadLookup.Found -> downloaded.payload.use { payload ->
                    if (payload.byteSize != descriptor.byteSize || payload.sha256 != descriptor.payloadSha256) {
                        if (transportAttempt == 1) failure = "RESULT_TRANSPORT_HASH_MISMATCH"
                    } else {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val body = payload.open().use { strictResultMapper.readValue(it, Map::class.java) }
                                as Map<String, Any?>
                            val result = NormalizationResultVerifier.verify(body, run, dispatch.id)
                            comparePayloadDescriptor(result, descriptor)
                            verified = result
                        } catch (error: ResultIntegrityFailure) { failure = error.code }
                        catch (_: Exception) { failure = "RESULT_SCHEMA_UNSUPPORTED" }
                    }
                }
                is NormalizationPayloadLookup.Retry -> failure = "TEMPORARY_DEPENDENCY_FAILURE"
                is NormalizationPayloadLookup.Rejected -> failure =
                    downloaded.code.takeIf { it == "RESULT_NOT_AVAILABLE" } ?: "INTERNAL_ERROR"
            }
        }
        if (failure != null) {
            if (failure == "TEMPORARY_DEPENDENCY_FAILURE") retry(run, dispatch, worker, failure!!)
            else terminal(run, dispatch, worker, failure!!)
            return true
        }
        val checked = verified ?: return true
        try {
            if (!recovered) {
                val base = stageBase(checked.public)
                transactions.execute {
                    if (!results.hasReadyLease(dispatch.id, worker, clock.instant())) throw LeaseLost()
                    results.saveStageBase(run.id, mapper.writeValueAsString(base))
                }
                for (batch in items(checked).chunked(100)) {
                    transactions.execute {
                        if (!results.hasReadyLease(dispatch.id, worker, clock.instant())) throw LeaseLost()
                        results.stageItems(run.id, batch)
                    }
                }
            }
            val staged = results.stagedCounts(run.id)
            for (digest in checked.sectionDigests) {
                val section = digest.getValue("sectionType") as String
                val declared = (digest.getValue("itemCount") as Number).toLong()
                if ((staged[section] ?: 0) != declared) throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
            }
            val countKeys = mapOf("positionAllocationCount" to "POSITION_ALLOCATIONS",
                "excludedReviewUnitCount" to "EXCLUDED_REVIEW_UNITS",
                "validationIssueCount" to "VALIDATION_ISSUES")
            for ((field, section) in countKeys) if (staged[section] != checked.counts[field])
                throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
            val persistedItems = results.loadItems(run.id)
            for (item in persistedItems) {
                val parsed = mapper.readValue(item.publicJson, Any::class.java)
                if (NormalizationResultCanonicalizer.hash(parsed) != item.contentHash)
                    throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
            }
            val reloaded = rehydrate(checked.public, persistedItems)
            val independentlyVerified = NormalizationResultVerifier.verify(reloaded, run, dispatch.id)
            comparePayloadDescriptor(independentlyVerified, descriptor)
            val published = transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked == null || !isCurrent(run, locked)) return@execute false
                if (!results.hasReadyLease(dispatch.id, worker, clock.instant())) return@execute false
                results.markValidated(run.id, clock.instant())
                val public = independentlyVerified.public
                results.publish(run, dispatch, worker, descriptor,
                    mapper.writeValueAsString(public["reconciliationManifest"]),
                    mapper.writeValueAsString(public["artifactCoverages"]),
                    mapper.writeValueAsString(public["capability"]),
                    mapper.writeValueAsString(public["qualityReport"]),
                    public["ledgerContentHash"] as String, clock.instant())
            } == true
            if (!published && transactions.execute {
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
        if (descriptor.resultSchemaVersion != NormalizationResultVerifier.RESULT_SCHEMA_V1 ||
            descriptor.resultHashSpecificationVersion != NormalizationResultVerifier.RESULT_HASH_V1 ||
            descriptor.inputHashSpecificationVersion != identity.hashSpecificationVersion)
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (descriptor.jobId != run.computeJobId || descriptor.logicalJobId != dispatch.id ||
            descriptor.runId != run.id || descriptor.attemptToken != run.attemptToken ||
            descriptor.deletionGeneration != run.input.deletionGeneration ||
            descriptor.inputSetHash != run.inputFingerprint ||
            !same(identity.targetVersionSet.toWireValue(), descriptor.targetVersionSet))
            throw ResultIntegrityFailure("INPUT_SET_HASH_MISMATCH")
        if (descriptor.byteSize !in 1..52_428_800L ||
            !Regex("[0-9a-f]{64}").matches(descriptor.payloadSha256))
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (!locallyComplete && !descriptor.expiresAt.isAfter(clock.instant()))
            throw ResultIntegrityFailure("RESULT_NOT_AVAILABLE")
    }

    private fun comparePayloadDescriptor(result: VerifiedNormalizationResult,
                                         descriptor: NormalizationResultDescriptor) {
        if (result.manifestHash != descriptor.reconciliationManifestHash)
            throw ResultIntegrityFailure("RECONCILIATION_MANIFEST_HASH_MISMATCH")
        if (result.resultHash != descriptor.resultHash)
            throw ResultIntegrityFailure("RESULT_HASH_MISMATCH")
        if (!same(result.counts, descriptor.counts)) throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
        if (!same(result.sectionDigests, descriptor.sectionDigests))
            throw ResultIntegrityFailure("RESULT_SECTION_HASH_MISMATCH")
    }

    private fun items(result: VerifiedNormalizationResult): Sequence<NormalizationStagedItem> = sequence {
        val public = result.public
        val manifest = public["reconciliationManifest"] as Map<*, *>
        val sources = mapOf(
            "ARTIFACT_COVERAGES" to (public["artifactCoverages"] to "artifactRole"),
            "CANONICAL_RECORDS" to (public["canonicalRecords"] to "recordId"),
            "SOURCE_EVIDENCE_SNAPSHOTS" to (public["sourceEvidenceSnapshots"] to "snapshotId"),
            "REVIEW_UNITS" to (manifest["reviewUnits"] to "reviewUnitId"),
            "RECONCILIATION_MATCHES" to (manifest["reconciliationMatches"] to "matchId"),
            "RECONCILIATION_ISSUES" to (manifest["reconciliationIssues"] to "issueId"))
        for ((section, source) in sources) for (raw in source.first as List<*>) {
            val item = raw as Map<*, *>
            val id = item[source.second] as? String ?: throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
            yield(staged(section, id, item))
        }
        for (capability in public["capability"] as List<*>) {
            val name = capability as? String ?: throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
            yield(staged("CAPABILITIES", name, name))
        }
        yield(staged("QUALITY_REPORT", "singleton", public["qualityReport"]))
        yield(staged("RECONCILIATION_MANIFEST", "singleton", manifest))
    }

    private fun staged(section: String, id: String, value: Any?): NormalizationStagedItem =
        NormalizationStagedItem(section, id, NormalizationResultCanonicalizer.hash(value),
            mapper.writeValueAsString(value))

    @Suppress("UNCHECKED_CAST")
    private fun stageBase(public: Map<String, Any?>): Map<String, Any?> {
        val manifest = public["reconciliationManifest"] as Map<String, Any?>
        return public + mapOf(
            "artifactCoverages" to emptyList<Any>(), "canonicalRecords" to emptyList<Any>(),
            "sourceEvidenceSnapshots" to emptyList<Any>(), "capability" to emptyList<Any>(),
            "qualityReport" to emptyMap<String, Any>(),
            "reconciliationManifest" to (manifest + mapOf(
                "reviewUnits" to emptyList<Any>(),
                "reconciliationMatches" to emptyList<Any>(),
                "reconciliationIssues" to emptyList<Any>(),
                "artifactCoverages" to emptyList<Any>(),
                "capability" to emptyList<Any>())))
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
            (descriptor.counts[field] as? Number)?.toLong() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rehydrate(base: Map<String, Any?>, staged: List<NormalizationStagedItem>): Map<String, Any?> {
        val grouped = staged.groupBy { it.sectionType }.mapValues { (_, values) -> values.map {
            mapper.readValue(it.publicJson, Any::class.java)
        } }
        val root = base.toMutableMap()
        val manifest = (base["reconciliationManifest"] as Map<String, Any?>).toMutableMap()
        val mappings = mapOf("ARTIFACT_COVERAGES" to "artifactCoverages",
            "CANONICAL_RECORDS" to "canonicalRecords",
            "SOURCE_EVIDENCE_SNAPSHOTS" to "sourceEvidenceSnapshots")
        for ((section, name) in mappings) root[name] = grouped[section] ?: emptyList<Any>()
        root["capability"] = grouped["CAPABILITIES"] ?: emptyList<Any>()
        root["qualityReport"] = grouped["QUALITY_REPORT"]?.singleOrNull()
            ?: throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
        val manifestBase = grouped["RECONCILIATION_MANIFEST"]?.singleOrNull()
            as? Map<String, Any?> ?: throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
        manifest.putAll(manifestBase)
        manifest["reviewUnits"] = grouped["REVIEW_UNITS"] ?: emptyList<Any>()
        manifest["reconciliationMatches"] = grouped["RECONCILIATION_MATCHES"] ?: emptyList<Any>()
        manifest["reconciliationIssues"] = grouped["RECONCILIATION_ISSUES"] ?: emptyList<Any>()
        root["reconciliationManifest"] = manifest
        return root
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
