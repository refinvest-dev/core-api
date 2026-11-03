package com.refinvest.core.tradingreview.application.importsession.create

import com.refinvest.core.tradingreview.application.support.requestFingerprint
import com.refinvest.core.tradingreview.application.support.validateIdempotencyKey
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingDataPolicy
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionResult
import com.refinvest.core.tradingreview.port.inbound.importsession.create.CreateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.create.CreateTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import java.time.Clock
import java.time.ZoneId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class CreateTradingImportSessionService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val sessions: TradingImportSessionStore,
    private val idempotency: TradingIdempotencyStore,
    private val versions: TradingVersionPins,
    private val clock: Clock,
) : CreateTradingImportSessionUseCase {
    @Transactional
    override fun execute(command: CreateTradingImportSessionCommand): TradingImportSessionResult =
        TradingImportSessionResult.fromDomain(createSession(command.bookId, command.sourceTimezone,
            command.reviewTimezone, command.mode, command.dataPolicyVersion, command.clientRequestReference, command.key))

    private fun createSession(bookId: TradingBookId, sourceTimezone: String, reviewTimezone: String,
                        mode: TradingPositionMode, dataPolicyVersion: String?, clientRequestReference: String?,
                        key: String): TradingImportSession {
        validateIdempotencyKey(key)
        if (sourceTimezone !in ZoneId.getAvailableZoneIds()) throw TradingFailure("SOURCE_TIMEZONE_INVALID", TradingFailureKind.INPUT_REJECTED)
        if (reviewTimezone !in ZoneId.getAvailableZoneIds()) throw TradingFailure("REVIEW_TIMEZONE_INVALID", TradingFailureKind.INPUT_REJECTED)
        if (dataPolicyVersion != null && dataPolicyVersion != POLICY.dataPolicyVersion)
            throw TradingFailure("VERSION_UNAVAILABLE", TradingFailureKind.INPUT_REJECTED)
        if (clientRequestReference != null && clientRequestReference.length > 100)
            throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val owner = members.currentMemberId()
        val initial = books.find(bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(initial.accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val book = books.find(bookId, owner, forUpdate = true) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        if (mode != TradingPositionMode.ONE_WAY || mode != book.positionMode)
            throw TradingFailure("POSITION_MODE_UNSUPPORTED", TradingFailureKind.INPUT_REJECTED)
        val fingerprint = requestFingerprint(bookId.value.toString(), sourceTimezone, reviewTimezone, mode.name,
            POLICY.dataPolicyVersion, clientRequestReference ?: "")
        return when (val claim = idempotency.claim(owner, "createTradingImportSession", key, fingerprint)) {
            is IdempotencyClaim.Replayed -> sessions.find(TradingImportSessionId(claim.resourceId), owner)
                ?.takeUnless { it.deletionPending }
                ?: throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
            IdempotencyClaim.New -> {
                val session = TradingImportSession.create(ids.importSessionId(), book, sourceTimezone,
                    reviewTimezone, mode, POLICY, versions.currentJson(), clock.instant())
                sessions.insert(session)
                idempotency.complete(owner, "createTradingImportSession", key, session.id.value)
                session
            }
        }
    }

    private companion object { val POLICY = TradingDataPolicy() }
}
