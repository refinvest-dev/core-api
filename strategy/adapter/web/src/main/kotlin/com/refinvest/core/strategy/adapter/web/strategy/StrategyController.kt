package com.refinvest.core.strategy.adapter.web.strategy

import com.refinvest.core.strategy.adapter.web.strategy.create.CreateStrategyRequest
import com.refinvest.core.strategy.adapter.web.strategy.create.CreateStrategyResponse
import com.refinvest.core.strategy.adapter.web.strategy.get.GetStrategyResponse
import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.port.inbound.strategy.create.CreateStrategyCommand
import com.refinvest.core.strategy.port.inbound.strategy.create.CreateStrategyUseCase
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyQuery
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/strategies")
class StrategyController(
    private val createStrategyUseCase: CreateStrategyUseCase,
    private val getStrategyUseCase: GetStrategyUseCase,
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

    @GetMapping("/{strategyId}")
    fun get(@PathVariable strategyId: Long): GetStrategyResponse {
        val result = getStrategyUseCase.execute(GetStrategyQuery(StrategyId(strategyId)))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Strategy not found")
        return GetStrategyResponse(
            id = result.id.value.toString(),
            name = result.name,
            createdAt = result.createdAt,
            latestVersionId = result.latestVersionId?.value?.toString(),
        )
    }
}
