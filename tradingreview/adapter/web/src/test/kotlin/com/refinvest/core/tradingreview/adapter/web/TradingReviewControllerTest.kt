package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import kotlin.test.Test

class TradingReviewControllerTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val account = TradingAccount.create(TradingAccountId(11), MemberId(1), TradingVenue.BINANCE, "Primary", now)
    private val book = TradingBook.create(TradingBookId(12), account, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
        TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
    private val deletion = TradingDeletion(13, MemberId(1), "TRADING_BOOK", 12, "BOOK_SUBTREE", "REQUESTED",
        1, 0, 1, now, now, now, now.plusSeconds(30L * 24 * 3600))

    private val accounts = object : TradingAccountUseCase {
        override fun create(venue: TradingVenue, displayName: String, key: String) = account
        override fun get(id: TradingAccountId) = account
        override fun list(page: Int, size: Int) = TradingPage(listOf(account), page, size, 1)
        override fun delete(id: TradingAccountId, key: String) = deletion
    }
    private val books = object : TradingBookUseCase {
        override fun create(accountId: TradingAccountId, displayName: String, productFamily: TradingProductFamily,
                            positionMode: TradingPositionMode, settlementAsset: TradingSettlementAsset,
                            reviewTimezone: String, key: String) = book
        override fun get(id: TradingBookId) = book
        override fun list(accountId: TradingAccountId, page: Int, size: Int) = TradingPage(listOf(book), page, size, 1)
        override fun delete(id: TradingBookId, key: String) = deletion
    }
    private val mvc = MockMvcBuilders.standaloneSetup(
        TradingAccountController(accounts, books), TradingBookController(books),
        TradingDeletionController(object : GetTradingDeletionRequestUseCase { override fun get(id: Long) = deletion }),
    ).setControllerAdvice(TradingReviewExceptionHandler()).build()

    @Test
    fun `account and book create follow the published response shape`() {
        mvc.perform(post("/trading-accounts").header("Idempotency-Key", "a")
            .contentType("application/json").content("""{"venue":"BINANCE","displayName":"Primary"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("11"))
            .andExpect(jsonPath("$.ownerMemberId").doesNotExist())
        mvc.perform(post("/trading-accounts/11/books").header("Idempotency-Key", "b")
            .contentType("application/json").content("""{"displayName":"Futures","productFamily":"LINEAR_PERPETUAL","positionMode":"ONE_WAY","settlementAsset":"USDT","reviewTimezone":"UTC"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.tradingAccountId").value("11"))
            .andExpect(jsonPath("$.productFamily").value("LINEAR_PERPETUAL"))
            .andExpect(jsonPath("$.ownerMemberId").doesNotExist())
    }

    @Test
    fun `list get delete and deletion status expose safe fields`() {
        mvc.perform(get("/trading-accounts?page=0&size=20"))
            .andExpect(status().isOk).andExpect(jsonPath("$.total").value(1))
        mvc.perform(get("/trading-books/12"))
            .andExpect(status().isOk).andExpect(jsonPath("$.id").value("12"))
        mvc.perform(delete("/trading-books/12").header("Idempotency-Key", "c"))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.state").value("REQUESTED"))
        mvc.perform(get("/trading-deletion-requests/13"))
            .andExpect(status().isOk).andExpect(jsonPath("$.affectedResources.books").value(1))
    }

    @Test
    fun `malformed public input is rejected without stack trace`() {
        mvc.perform(post("/trading-accounts").header("Idempotency-Key", "a")
            .contentType("application/json").content("""{"venue":"OTHER","displayName":"Primary"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
            .andExpect(jsonPath("$.traceId").exists())
    }
}
