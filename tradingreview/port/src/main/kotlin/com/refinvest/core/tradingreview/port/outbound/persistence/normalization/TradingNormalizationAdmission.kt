package com.refinvest.core.tradingreview.port.outbound.persistence.normalization

import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*

interface TradingNormalizationAdmission {
    /** False leaves Session, Run, attempt, dispatch and audit unchanged. */
    fun admit(admission: NormalizationAdmission): Boolean
}
