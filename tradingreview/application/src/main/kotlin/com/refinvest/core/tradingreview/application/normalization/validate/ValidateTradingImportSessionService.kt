package com.refinvest.core.tradingreview.application.normalization.validate

import com.refinvest.core.tradingreview.application.NormalizationInputFingerprint
import com.refinvest.core.tradingreview.application.normalization.NormalizationAdmissionFactory
import com.refinvest.core.tradingreview.application.normalization.NormalizationAdmissionGuard
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionResult
import com.refinvest.core.tradingreview.port.inbound.normalization.validate.ValidateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.normalization.validate.ValidateTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationAdmission
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationRunReader
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class ValidateTradingImportSessionService(
    private val guard: NormalizationAdmissionGuard,
    private val factory: NormalizationAdmissionFactory,
    private val sessions: TradingImportSessionStore,
    private val idempotency: TradingIdempotencyStore,
    private val runs: TradingNormalizationRunReader,
    private val admissions: TradingNormalizationAdmission,
    private val clock: Clock,
) : ValidateTradingImportSessionUseCase {
    @Transactional
    override fun execute(command: ValidateTradingImportSessionCommand): TradingImportSessionResult {
        val context = guard.lock(command.id, command.key)
        val session = context.session
        val owner = session.owner
        val pinnedVersions = factory.pinnedVersions(session, command.targetVersionSetJson)
        val publicPayload = NormalizationInputFingerprint.hashParts(command.id.value.toString(),
            command.targetVersionSetJson?.let(factory::canonical) ?: "", "validate")
        when (idempotency.claim(owner, OPERATION, command.key, publicPayload)) {
            is IdempotencyClaim.Replayed -> return TradingImportSessionResult.fromDomain(
                sessions.find(command.id, owner)
                    ?: throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE))
            IdempotencyClaim.New -> Unit
        }
        if (session.status == TradingImportStatus.VALIDATING) {
            if (runs.findActive(command.id)?.id != session.activeNormalizationRunId)
                throw TradingFailure("RESOURCE_STATE_CONFLICT", TradingFailureKind.CONFLICT)
            idempotency.complete(owner, OPERATION, command.key, command.id.value)
            return TradingImportSessionResult.fromDomain(session)
        }
        if (session.status != TradingImportStatus.RECEIVED)
            throw TradingFailure("RESOURCE_STATE_CONFLICT", TradingFailureKind.CONFLICT)
        factory.requireExecutable(pinnedVersions)
        val admission = factory.validation(context, pinnedVersions, clock.instant())
        if (!admissions.admit(admission))
            throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT)
        idempotency.complete(owner, OPERATION, command.key, command.id.value)
        return TradingImportSessionResult.fromDomain(admission.admitted)
    }

    private companion object { const val OPERATION = "validateTradingImportSession" }
}
