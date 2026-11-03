package com.refinvest.core.tradingreview.adapter.web.account

import com.refinvest.core.tradingreview.adapter.web.deletion.TradingDeletionResponse
import com.refinvest.core.tradingreview.adapter.web.pagination.TradingPageResponse
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountCommand
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.delete.DeleteTradingAccountCommand
import com.refinvest.core.tradingreview.port.inbound.account.delete.DeleteTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.get.GetTradingAccountQuery
import com.refinvest.core.tradingreview.port.inbound.account.get.GetTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.list.ListTradingAccountsQuery
import com.refinvest.core.tradingreview.port.inbound.account.list.ListTradingAccountsUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
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
@RequestMapping("/trading-accounts")
class TradingAccountController(
    private val createAccount: CreateTradingAccountUseCase,
    private val getAccount: GetTradingAccountUseCase,
    private val listAccounts: ListTradingAccountsUseCase,
    private val deleteAccount: DeleteTradingAccountUseCase,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader("Idempotency-Key") key: String,
               @Valid @RequestBody request: CreateTradingAccountRequest) =
        TradingAccountResponse.from(createAccount.execute(CreateTradingAccountCommand(request.venue, request.displayName, key)))

    @GetMapping("/{tradingAccountId}")
    fun get(@PathVariable tradingAccountId: Long) = TradingAccountResponse.from(getAccount.execute(GetTradingAccountQuery(TradingAccountId(tradingAccountId))))

    @GetMapping
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): TradingPageResponse<TradingAccountResponse> {
        val result = listAccounts.execute(ListTradingAccountsQuery(page, size))
        return TradingPageResponse(result.items.map(TradingAccountResponse::from), result.page, result.size, result.total)
    }

    @DeleteMapping("/{tradingAccountId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable tradingAccountId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(deleteAccount.execute(DeleteTradingAccountCommand(TradingAccountId(tradingAccountId), key)))
}
