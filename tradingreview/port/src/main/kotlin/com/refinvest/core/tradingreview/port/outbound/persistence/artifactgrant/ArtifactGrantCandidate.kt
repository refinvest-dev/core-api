package com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant


data class ArtifactGrantCandidate(
    val objectKey: String?, val byteSize: Long, val sha256: String, val mediaType: String, val role: String,
)
