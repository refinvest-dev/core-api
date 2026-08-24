package com.refinvest.core.strategy.adapter.web.strategy

import com.refinvest.core.strategy.adapter.web.strategy.create.CreateStrategyRequest
import com.refinvest.core.strategy.adapter.web.strategy.create.CreateStrategyResponse
import com.refinvest.core.strategy.adapter.web.strategy.define.DefineStrategyVersionRequest
import com.refinvest.core.strategy.adapter.web.strategy.define.DefineStrategyVersionResponse
import com.refinvest.core.strategy.adapter.web.strategy.get.GetStrategyResponse
import com.refinvest.core.strategy.adapter.web.strategy.get.GetStrategyVersionResponse
import com.refinvest.core.strategy.adapter.web.strategy.list.ListStrategiesResponse
import com.refinvest.core.strategy.adapter.web.strategy.preview.PreviewStrategyRequest
import com.refinvest.core.strategy.adapter.web.strategy.preview.PreviewStrategyResponse
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.inbound.create.CreateStrategyCommand
import com.refinvest.core.strategy.port.inbound.create.CreateStrategyUseCase
import com.refinvest.core.strategy.port.inbound.get.GetStrategyQuery
import com.refinvest.core.strategy.port.inbound.get.GetStrategyUseCase
import com.refinvest.core.strategy.port.inbound.list.ListStrategiesQuery
import com.refinvest.core.strategy.port.inbound.list.ListStrategiesUseCase
import com.refinvest.core.strategy.port.inbound.preview.PreviewStrategyUseCase
import com.refinvest.core.strategy.port.inbound.define.DefineStrategyVersionUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/strategies")
class StrategyController(
    private val createStrategyUseCase: CreateStrategyUseCase,
    private val getStrategyUseCase: GetStrategyUseCase,
    private val defineStrategyVersionUseCase: DefineStrategyVersionUseCase,
    private val listStrategiesUseCase: ListStrategiesUseCase,
    private val previewStrategyUseCase: PreviewStrategyUseCase,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateStrategyRequest): CreateStrategyResponse {
        val result = createStrategyUseCase.execute(CreateStrategyCommand(name = request.name))
        return CreateStrategyResponse(
            id = result.id.value.toString(),
            name = request.name,
            createdAt = result.createdAt,
            latestVersionId = null,
        )
    }

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ListStrategiesResponse = try {
        ListStrategiesResponse.from(listStrategiesUseCase.execute(ListStrategiesQuery(page, size)))
    } catch (exception: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
    }

    @GetMapping("/{strategyId}")
    fun get(@PathVariable strategyId: Long): GetStrategyResponse {
        val result = getStrategyUseCase.execute(GetStrategyQuery(StrategyId(strategyId)))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Strategy not found")
        return GetStrategyResponse(
            id = result.id.value.toString(),
            name = result.name,
            createdAt = result.createdAt,
            latestVersionId = result.latestVersionId?.value?.toString(),
            versions = result.versions.map { GetStrategyVersionResponse.from(result.id.value.toString(), it) },
        )
    }

    @PostMapping("/{strategyId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    fun defineVersion(
        @PathVariable strategyId: Long,
        @Valid @RequestBody request: DefineStrategyVersionRequest,
    ): DefineStrategyVersionResponse = try {
        defineStrategyVersionUseCase.execute(request.toCommand(StrategyId(strategyId)))
            ?.let(DefineStrategyVersionResponse::from)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Strategy not found")
    } catch (exception: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
    }

    @PostMapping("/{strategyId}/versions/preview")
    fun preview(
        @PathVariable strategyId: Long,
        @RequestBody request: PreviewStrategyRequest,
    ): PreviewStrategyResponse = previewStrategyUseCase
        .execute(request.toCommand(StrategyId(strategyId)))
        ?.let(PreviewStrategyResponse::from)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Strategy not found")
}
