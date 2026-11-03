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

sealed interface NormalizationPayloadLookup {
    data class Found(val payload: NormalizationPayloadFile) : NormalizationPayloadLookup
    data class Retry(val code: String) : NormalizationPayloadLookup
    data class Rejected(val code: String) : NormalizationPayloadLookup
}
