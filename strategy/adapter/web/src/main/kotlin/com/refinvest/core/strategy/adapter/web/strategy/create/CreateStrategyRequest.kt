package com.refinvest.core.strategy.adapter.web.strategy.create

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateStrategyRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
)
