package com.refinvest.core.tradingreview.port.normalization.compute

import com.refinvest.core.tradingreview.port.artifactgrant.ArtifactReadGrant

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

interface TradingNormalizationComputeClient {
    fun submit(run: NormalizationRun, dispatch: NormalizationDispatch,
               grants: List<ArtifactReadGrant>): NormalizationSubmission
    fun status(run: NormalizationRun, dispatch: NormalizationDispatch): NormalizationStatusLookup
    fun updateGrants(run: NormalizationRun, dispatch: NormalizationDispatch,
                     current: NormalizationJobStatus, nextAttemptToken: String,
                     grants: List<ArtifactReadGrant>, updateKey: String): NormalizationGrantUpdate
}

/** Transport bytes stay outside durable state; the descriptor contains only safe opaque identities and digests. */
