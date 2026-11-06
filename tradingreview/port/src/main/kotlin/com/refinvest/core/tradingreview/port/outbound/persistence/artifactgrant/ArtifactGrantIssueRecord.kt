package com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant

import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactGrantBinding
import java.time.Instant

data class ArtifactGrantIssueRecord(
    val binding: ArtifactGrantBinding, val objectKey: String, val tokenHash: String,
    val createdAt: Instant, val expiresAt: Instant,
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(tokenHash))
        require(expiresAt.isAfter(createdAt) && !expiresAt.isAfter(createdAt.plusSeconds(300)))
    }
}
