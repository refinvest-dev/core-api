package com.refinvest.core.tradingreview.port.artifactgrant

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

interface ArtifactAccessGrantIssuer {
    fun issueReadGrant(artifact: NormalizationArtifactInput, logicalJobId: String,
                       sessionId: TradingImportSessionId, attemptToken: String,
                       deletionGeneration: Long): ArtifactReadGrant
}
