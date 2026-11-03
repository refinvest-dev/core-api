package com.refinvest.core.tradingreview.adapter.web.importsession

import com.refinvest.core.tradingreview.adapter.web.pagination.TradingPageResponse
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.port.inbound.importsession.create.CreateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.create.CreateTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionQuery
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.list.ListTradingImportsQuery
import com.refinvest.core.tradingreview.port.inbound.importsession.list.ListTradingImportsUseCase
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
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/trading-books/{tradingBookId}/imports")
class TradingBookImportController(
    private val createImport: CreateTradingImportSessionUseCase,
    private val getImport: GetTradingImportSessionUseCase,
    private val listImports: ListTradingImportsUseCase,
    private val mapper: ObjectMapper,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable tradingBookId: Long, @RequestHeader("Idempotency-Key") key: String,
               @Valid @RequestBody request: CreateTradingImportSessionRequest): TradingImportSessionResponse {
        val session = createImport.execute(CreateTradingImportSessionCommand(TradingBookId(tradingBookId), request.sourceTimezone, request.reviewTimezone, request.declaredPositionMode, request.dataPolicyVersion, request.clientRequestReference, key))
        return TradingImportSessionResponse.from(getImport.execute(GetTradingImportSessionQuery(session.id)), mapper)
    }

    @GetMapping
    fun list(@PathVariable tradingBookId: Long, @RequestParam(defaultValue = "0") page: Int,
             @RequestParam(defaultValue = "20") size: Int): TradingPageResponse<TradingImportSessionResponse> {
        val result = listImports.execute(ListTradingImportsQuery(TradingBookId(tradingBookId), page, size))
        return TradingPageResponse(result.items.map { TradingImportSessionResponse.from(it, mapper) },
            result.page, result.size, result.total)
    }
}
