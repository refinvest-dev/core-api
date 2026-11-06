package com.refinvest.core.tradingreview.port.outbound.artifactgrant

import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId

interface ArtifactAccessGrantIssuer {
    fun issueReadGrant(artifact: NormalizationArtifactInput, logicalJobId: String,
                       sessionId: TradingImportSessionId, attemptToken: String,
                       deletionGeneration: Long): ArtifactReadGrant
}
