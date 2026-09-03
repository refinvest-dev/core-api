package com.refinvest.core.asset.domain

import java.math.BigDecimal
import java.time.LocalDate

data class CorporateAction(
    val type: CorporateActionType,
    val effectiveDate: LocalDate,
    val ratio: BigDecimal?,
    val amount: BigDecimal?,
)
