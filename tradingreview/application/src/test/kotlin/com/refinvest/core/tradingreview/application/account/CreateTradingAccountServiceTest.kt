package com.refinvest.core.tradingreview.application.account

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.application.account.create.CreateTradingAccountService
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountCommand
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CreateTradingAccountServiceTest {
    private val owner = MemberId(11)
    private val now = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val members = object : TradingMemberProvider { override fun currentMemberId() = owner }

    @Test
    fun `creation claims before allocating and saving then completes with the aggregate id`() {
        val calls = mutableListOf<String>()
        val ids = port<TradingIdGenerator> { name, _ ->
            assertEquals("accountId", name)
            calls += "id"
            21L
        }
        val accounts = port<TradingAccountStore> { name, args ->
            assertEquals("insert", name)
            val account = args[0] as TradingAccount
            assertEquals(owner, account.owner)
            assertEquals(now, account.createdAt)
            calls += "insert"
            Unit
        }
        val idempotency = port<TradingIdempotencyStore> { name, args ->
            assertEquals(listOf(owner.value, "createTradingAccount", "request-1"), args.take(3))
            calls += name
            when (name) {
                "claim" -> {
                    // Fixed UTF-8 vector: byte lengths, NUL separators and field order are part of replay identity.
                    assertEquals("f38b3f15a266ba192070f5f5bfe13fbace3bc8bb56f5ce213209ff293a174f72", args[3])
                    IdempotencyClaim.New
                }
                "complete" -> { assertEquals(21L, args[3]); Unit }
                else -> error(name)
            }
        }
        val result = CreateTradingAccountService(members, ids, accounts, idempotency, clock)
            .execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "한글 account", "request-1"))

        assertEquals(TradingAccountId(21), result.id)
        assertEquals(listOf("claim", "id", "insert", "complete"), calls)
    }

    @Test
    fun `replay restores an archived account without allocating or persisting another aggregate`() {
        val account = TradingAccount.restore(TradingAccountId(21), owner, TradingVenue.BINANCE, "account",
            TradingLifecycleStatus.ARCHIVED, 0, now, now, 3)
        val accounts = port<TradingAccountStore> { name, args ->
            assertEquals("find", name)
            assertEquals(listOf(account.id.value, owner.value, false), args.toList())
            account
        }
        val idempotency = port<TradingIdempotencyStore> { name, _ ->
            assertEquals("claim", name)
            IdempotencyClaim.Replayed(account.id.value)
        }

        val result = CreateTradingAccountService(members, port(), accounts, idempotency, clock)
            .execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "account", "request-1"))

        assertEquals(account.id, result.id)
        assertEquals(TradingLifecycleStatus.ARCHIVED, result.status)
    }

    @Test
    fun `invalid key fails before authentication or any persistence call`() {
        val service = CreateTradingAccountService(port(), port(), port(), port(), clock)
        assertEquals("IDEMPOTENCY_KEY_INVALID", assertFailsWith<TradingFailure> {
            service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "account", "invalid key"))
        }.code)
    }

    @Test
    fun `failed aggregate insert never completes the idempotency claim`() {
        val failure = IllegalStateException("synthetic insert failure")
        val ids = port<TradingIdGenerator> { _, _ -> 21L }
        val idempotency = port<TradingIdempotencyStore> { name, _ ->
            assertEquals("claim", name)
            IdempotencyClaim.New
        }
        val accounts = port<TradingAccountStore> { _, _ -> throw failure }
        val service = CreateTradingAccountService(members, ids, accounts, idempotency, clock)
        assertSame(failure, assertFailsWith<IllegalStateException> {
            service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "account", "request-1"))
        })
    }

    private inline fun <reified T> port(noinline call: (String, Array<out Any?>) -> Any? = { name, _ ->
        error("Unexpected ${T::class.java.simpleName}.$name")
    }): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        call(method.name.substringBefore('-'), args ?: emptyArray())
    } as T
}
