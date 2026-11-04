package com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant


interface ArtifactGrantRedemptionStore {
    /** Atomically spends a live credential and returns only the authorized raw descriptor. */
    fun consume(request: ArtifactGrantConsumption): ArtifactGrantReadSource?
}
