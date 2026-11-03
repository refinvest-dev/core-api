package com.refinvest.core.tradingreview.port.outbound.artifactgrant

import com.refinvest.core.tradingreview.domain.*

interface ArtifactReadGrantRedeemer { fun redeem(token: String): ArtifactRedemption }
