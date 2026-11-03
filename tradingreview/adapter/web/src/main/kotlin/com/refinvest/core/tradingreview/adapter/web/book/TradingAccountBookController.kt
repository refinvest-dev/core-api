package com.refinvest.core.tradingreview.adapter.web.book

import com.refinvest.core.tradingreview.adapter.web.pagination.TradingPageResponse
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.port.inbound.book.create.CreateTradingBookCommand
import com.refinvest.core.tradingreview.port.inbound.book.create.CreateTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.book.list.ListTradingBooksQuery
import com.refinvest.core.tradingreview.port.inbound.book.list.ListTradingBooksUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/trading-accounts/{tradingAccountId}/books")
class TradingAccountBookController(
    private val createBook: CreateTradingBookUseCase,
    private val listBooks: ListTradingBooksUseCase,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createBook(@PathVariable tradingAccountId: Long, @RequestHeader("Idempotency-Key") key: String,
                   @Valid @RequestBody request: CreateTradingBookRequest) = TradingBookResponse.from(
        createBook.execute(CreateTradingBookCommand(TradingAccountId(tradingAccountId), request.displayName, request.productFamily, request.positionMode, request.settlementAsset, request.reviewTimezone, key)))

    @GetMapping
    fun listBooks(@PathVariable tradingAccountId: Long, @RequestParam(defaultValue = "0") page: Int,
                  @RequestParam(defaultValue = "20") size: Int): TradingPageResponse<TradingBookResponse> {
        val result = listBooks.execute(ListTradingBooksQuery(TradingAccountId(tradingAccountId), page, size))
        return TradingPageResponse(result.items.map(TradingBookResponse::from), result.page, result.size, result.total)
    }
}
