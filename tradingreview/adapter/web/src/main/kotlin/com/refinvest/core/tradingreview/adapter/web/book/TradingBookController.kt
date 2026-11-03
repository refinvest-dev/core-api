package com.refinvest.core.tradingreview.adapter.web.book

import com.refinvest.core.tradingreview.adapter.web.deletion.TradingDeletionResponse
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.port.inbound.book.delete.DeleteTradingBookCommand
import com.refinvest.core.tradingreview.port.inbound.book.delete.DeleteTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.book.get.GetTradingBookQuery
import com.refinvest.core.tradingreview.port.inbound.book.get.GetTradingBookUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/trading-books")
class TradingBookController(
    private val getBook: GetTradingBookUseCase,
    private val deleteBook: DeleteTradingBookUseCase,
) {
    @GetMapping("/{tradingBookId}")
    fun get(@PathVariable tradingBookId: Long) = TradingBookResponse.from(getBook.execute(GetTradingBookQuery(TradingBookId(tradingBookId))))

    @DeleteMapping("/{tradingBookId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable tradingBookId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(deleteBook.execute(DeleteTradingBookCommand(TradingBookId(tradingBookId), key)))
}
