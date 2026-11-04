package com.refinvest.core.tradingreview.application.normalization.retry

import com.refinvest.core.tradingreview.application.NormalizationInputFingerprint
import com.refinvest.core.tradingreview.application.normalization.NormalizationAdmissionFactory
import com.refinvest.core.tradingreview.application.normalization.NormalizationAdmissionGuard
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionResult
import com.refinvest.core.tradingreview.port.inbound.normalization.retry.RetryTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.normalization.retry.RetryTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationAdmission
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class RetryTradingImportSessionService(
    private val guard: NormalizationAdmissionGuard,
    private val factory: NormalizationAdmissionFactory,
    private val sessions: TradingImportSessionStore,
    private val idempotency: TradingIdempotencyStore,
    private val admissions: TradingNormalizationAdmission,
    private val clock: Clock,
) : RetryTradingImportSessionUseCase {
    @Transactional
    override fun execute(command: RetryTradingImportSessionCommand): TradingImportSessionResult {
        val context = guard.lock(command.id, command.key)
        val session = context.session
        val owner = session.owner
        val pinnedVersions = factory.pinnedVersions(session, null)
        val publicPayload = NormalizationInputFingerprint.hashParts(command.id.value.toString(),
            "", "retry")
        when (idempotency.claim(owner, OPERATION, command.key, publicPayload)) {
            is IdempotencyClaim.Replayed -> return TradingImportSessionResult.fromDomain(
                sessions.find(command.id, owner)
                    ?: throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE))
            IdempotencyClaim.New -> Unit
        }
        if (session.status != TradingImportStatus.PROCESSING_FAILED || session.failure?.retryable != true)
            throw TradingFailure("RESOURCE_STATE_CONFLICT", TradingFailureKind.CONFLICT)
        factory.requireExecutable(pinnedVersions)
        val admission = factory.retry(context, pinnedVersions, clock.instant())
        if (!admissions.admit(admission))
            throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT)
        idempotency.complete(owner, OPERATION, command.key, command.id.value)
        return TradingImportSessionResult.fromDomain(admission.admitted)
    }

    private companion object { const val OPERATION = "retryTradingImportSession" }
}
