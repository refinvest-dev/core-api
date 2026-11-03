package com.refinvest.core.tradingreview.adapter.web.importsession

import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import jakarta.validation.constraints.NotBlank

data class CreateTradingImportSessionRequest(
    @field:NotBlank val sourceTimezone: String,
    @field:NotBlank val reviewTimezone: String,
    val declaredPositionMode: TradingPositionMode,
    val dataPolicyVersion: String? = null,
    val clientRequestReference: String? = null,
)
