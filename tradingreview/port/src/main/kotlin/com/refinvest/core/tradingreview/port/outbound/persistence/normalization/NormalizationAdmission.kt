package com.refinvest.core.tradingreview.port.outbound.persistence.normalization

import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*

data class NormalizationAdmission(
    val expected: TradingImportSession,
    val admitted: TradingImportSession,
    val dispatch: NormalizationDispatch,
)
