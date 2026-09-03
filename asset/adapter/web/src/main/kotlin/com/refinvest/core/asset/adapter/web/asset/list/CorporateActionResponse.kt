package com.refinvest.core.asset.adapter.web.asset.list

import com.refinvest.core.asset.domain.CorporateAction
import java.math.BigDecimal
import java.time.LocalDate

data class CorporateActionResponse(
    val type: String,
    val effectiveDate: LocalDate,
    val ratio: BigDecimal?,
    val amount: BigDecimal?,
) {
    companion object {
        fun from(action: CorporateAction) = CorporateActionResponse(
            action.type.name,
            action.effectiveDate,
            action.ratio,
            action.amount,
        )
    }
}
