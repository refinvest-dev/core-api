package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.adapter.web.account.TradingAccountController
import com.refinvest.core.tradingreview.adapter.web.book.TradingBookController
import com.refinvest.core.tradingreview.adapter.web.error.TradingReviewExceptionHandler
import com.refinvest.core.tradingreview.port.common.ownerquery.*
import com.refinvest.core.tradingreview.port.inbound.ownerquery.*
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.get.GetTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.list.ListTradingAccountsUseCase
import com.refinvest.core.tradingreview.port.inbound.account.delete.DeleteTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.book.get.GetTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.book.delete.DeleteTradingBookUseCase
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import kotlin.test.Test

class OwnerQueryProxyControllerTest {
    private val account=OwnerMetadataResult.Account("9007199254740993","BINANCE","owner","ACTIVE",Instant.parse("2026-11-24T00:00:00Z"))

    private fun mvc(proxy:ProxyOwnerMetadataUseCase)=MockMvcBuilders.standaloneSetup(
        TradingAccountController(CreateTradingAccountUseCase { error("legacy create") },GetTradingAccountUseCase {error("legacy get")},
            ListTradingAccountsUseCase {error("legacy list")},DeleteTradingAccountUseCase {error("legacy delete")},proxy),
        TradingBookController(GetTradingBookUseCase {error("legacy book")},DeleteTradingBookUseCase {error("legacy delete")},proxy)
    ).setControllerAdvice(TradingReviewExceptionHandler(proxy)).build()

    @Test fun `configured proxy returns typed asynchronous metadata without legacy access`() {
        val mvc=mvc { query ->
            kotlin.test.assertEquals("9007199254740993",query.resourceId)
            OwnerMetadataCall.completed(account)
        }
        val pending=mvc.perform(get("/trading-accounts/9007199254740993").header("X-Owner-ID","forged"))
            .andExpect(request().asyncStarted()).andReturn()
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.id").value("9007199254740993"))
            .andExpect(jsonPath("$.ownerId").doesNotExist())
    }

    @Test fun `known upstream failure is rendered with safe registry fields`() {
        val mvc=mvc { OwnerMetadataCall.completed(OwnerMetadataResult.Failure(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR)) }
        val pending=mvc.perform(get("/trading-books/1")).andExpect(request().asyncStarted()).andReturn()
        mvc.perform(asyncDispatch(pending)).andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("UPSTREAM_SERVICE_ERROR"))
            .andExpect(jsonPath("$.retryable").value(false))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.userAction").doesNotExist())
    }

    @Test fun `invalid GET and implicit HEAD never call configured proxy`() {
        val mvc=mvc { error("must not call") }
        mvc.perform(get("/trading-accounts/1?ownerId=2")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mvc.perform(get("/trading-accounts/not-a-number")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mvc.perform(head("/trading-accounts/1")).andExpect(status().isNotFound)
    }
}
