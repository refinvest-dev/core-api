package com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant

import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactGrantBinding
import java.time.Instant

interface ArtifactGrantIssueStore {
    fun loadEligible(binding: ArtifactGrantBinding, observedAt: Instant): ArtifactGrantCandidate?
    /** Rechecks ownership, current execution, raw lifetime and descriptor at the recording statement. */
    fun recordIfEligible(record: ArtifactGrantIssueRecord): Boolean
}
