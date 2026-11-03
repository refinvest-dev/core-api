package com.refinvest.core.tradingreview.port.normalization.result

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultDeliveryPolicy
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultVersionPin
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import java.io.InputStream
import java.time.Duration
import java.time.Instant

sealed interface NormalizationResultLookup {
    data class Found(val descriptor: NormalizationResultDescriptor) : NormalizationResultLookup
    data class Retry(val code: String) : NormalizationResultLookup
    data class Rejected(val code: String) : NormalizationResultLookup
}

/** A temporary HTTP entity body. Production downloads use encrypted spool bytes. */
