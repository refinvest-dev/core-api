package com.refinvest.core.tradingreview.application.normalization

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.application.normalization.retry.RetryTradingImportSessionService
import com.refinvest.core.tradingreview.application.normalization.validate.ValidateTradingImportSessionService
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.normalization.retry.RetryTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.normalization.validate.ValidateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingNormalizationIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingNormalizationSettings
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationAdmission
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationRunReader
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationTransitionGate
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import tools.jackson.databind.json.JsonMapper

class NormalizationAdmissionServiceTest {
    @Test
    fun `both operation replays load the current aggregate without consulting executable versions or allocating ids`() {
        for (retry in listOf(false, true)) {
            val fixture = Fixture(replay = true)
            assertEquals(fixture.session.id, fixture.execute(retry).id)
            assertEquals(listOf("gate", "session:false", "book:false", "account:true", "book:true",
                "session:true", "claim", "session:false"), fixture.calls)
        }
    }

    @Test
    fun `validation rejects a changed or duplicate target version before claiming even a replayed key`() {
        for (target in listOf("{\"next\":true}", "{\"v\":1,\"v\":1}")) {
            val fixture = Fixture(replay = true)
            assertEquals("VERSION_UNAVAILABLE", assertFailsWith<TradingFailure> {
                fixture.execute(retry = false, target = target)
            }.code)
            assertEquals("session:true", fixture.calls.last())
        }
    }

    @Test
    fun `both operations reject a paused transition before ownership lookup or idempotency`() {
        for (retry in listOf(false, true)) {
            val fixture = Fixture(paused = true)
            val failure = assertFailsWith<TradingFailure> { fixture.execute(retry) }
            assertEquals("TEMPORARY_DEPENDENCY_FAILURE", failure.code)
            assertEquals(TradingFailureKind.DEPENDENCY_UNAVAILABLE, failure.kind)
            assertEquals(listOf("gate"), fixture.calls)
        }
    }

    @Test
    fun `retry with a fresh key rejects a received session without creating an execution`() {
        val fixture = Fixture()
        assertEquals("RESOURCE_STATE_CONFLICT", assertFailsWith<TradingFailure> { fixture.execute(retry = true) }.code)
        assertEquals("claim", fixture.calls.last())
    }

    private class Fixture(replay: Boolean = false, paused: Boolean = false) {
        val calls = mutableListOf<String>()
        private val now = Instant.parse("2026-06-01T00:00:00Z")
        private val owner = MemberId(11)
        private val account = TradingAccount.create(TradingAccountId(21), owner, TradingVenue.BINANCE, "A", now)
        private val book = TradingBook.create(TradingBookId(31), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        val session = TradingImportSession.create(TradingImportSessionId(41), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
        private val sessions = port<TradingImportSessionStore> { name, args ->
            assertEquals("find", name)
            calls += "session:${args.last()}"
            session
        }
        private val guard = NormalizationAdmissionGuard(
            object : TradingMemberProvider { override fun currentMemberId() = owner },
            port<TradingAccountStore> { name, args ->
                assertEquals("find", name); calls += "account:${args.last()}"; account
            }, port<TradingBookStore> { name, args ->
                assertEquals("find", name); calls += "book:${args.last()}"; book
            }, sessions, port<TradingNormalizationTransitionGate> { name, _ ->
                assertEquals("requireAdmission", name)
                calls += "gate"
                if (paused) throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE)
                Unit
            })
        // Every configuration, ID, admission or active-run call is unexpected for these rejection/replay paths.
        private val factory = NormalizationAdmissionFactory(port<TradingVersionPins>(), port<TradingNormalizationSettings>(),
            JsonMapper.builder().build(), port<TradingNormalizationIdGenerator>())
        private val idempotency = port<TradingIdempotencyStore> { name, args ->
            assertEquals("claim", name)
            assertEquals(owner.value, args[0])
            assertEquals("request-1", args[2])
            val expected = when (args[1]) {
                "validateTradingImportSession" -> "5233761e1cf284fac36089e7f9218283a42966d06f68ce7596b7964587e1eb89"
                "retryTradingImportSession" -> "9eb3ea545469bf4755df6039391a427b7786462e74d75c1268b0cb58c990a0ad"
                else -> error("Unexpected idempotency operation")
            }
            assertEquals(expected, args[3])
            calls += "claim"
            if (replay) IdempotencyClaim.Replayed(session.id.value) else IdempotencyClaim.New
        }
        private val validation = ValidateTradingImportSessionService(guard, factory, sessions, idempotency,
            port<TradingNormalizationRunReader>(), port<TradingNormalizationAdmission>(), Clock.fixed(now, ZoneOffset.UTC))
        private val retryService = RetryTradingImportSessionService(guard, factory, sessions, idempotency,
            port<TradingNormalizationAdmission>(), Clock.fixed(now, ZoneOffset.UTC))

        fun execute(retry: Boolean, target: String? = null) =
            if (retry) retryService.execute(RetryTradingImportSessionCommand(session.id, "request-1"))
            else validation.execute(ValidateTradingImportSessionCommand(session.id, target, "request-1"))
    }

    private companion object {
        inline fun <reified T> port(noinline call: (String, Array<out Any?>) -> Any? = { name, _ ->
            error("Unexpected ${T::class.java.simpleName}.$name")
        }): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name.substringBefore('-'), args ?: emptyArray())
        } as T
    }
}
