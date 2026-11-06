package com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant

import java.time.Instant

data class ArtifactGrantConsumption(val tokenHash: String, val observedAt: Instant)
