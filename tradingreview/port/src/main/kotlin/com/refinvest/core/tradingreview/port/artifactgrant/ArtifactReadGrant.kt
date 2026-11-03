package com.refinvest.core.tradingreview.port.artifactgrant

import com.refinvest.core.tradingreview.domain.*
import java.time.Instant

data class ArtifactReadGrant(
    val coreArtifactId: String,
    val role: String,
    val rawSha256: String,
    val byteSize: Long,
    val contentType: String,
    val accessUri: String,
    val expiresAt: Instant,
)
