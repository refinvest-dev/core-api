package com.refinvest.core.tradingreview.port.outbound.compute.normalization

import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactReadGrant

import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.*

interface TradingNormalizationComputeClient {
    fun submit(run: NormalizationRun, dispatch: NormalizationDispatch,
               grants: List<ArtifactReadGrant>): NormalizationSubmission
    fun status(run: NormalizationRun, dispatch: NormalizationDispatch): NormalizationStatusLookup
    fun updateGrants(run: NormalizationRun, dispatch: NormalizationDispatch,
                     current: NormalizationJobStatus, nextAttemptToken: String,
                     grants: List<ArtifactReadGrant>, updateKey: String): NormalizationGrantUpdate
}

/** Transport bytes stay outside durable state; the descriptor contains only safe opaque identities and digests. */
