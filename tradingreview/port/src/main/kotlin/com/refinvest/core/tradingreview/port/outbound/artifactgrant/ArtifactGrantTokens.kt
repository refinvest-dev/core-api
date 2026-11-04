package com.refinvest.core.tradingreview.port.outbound.artifactgrant


interface ArtifactGrantTokens {
    fun requireIssuanceAvailable()
    fun issue(): IssuedArtifactGrantToken
    fun verifiedHash(token: String): String?
}
