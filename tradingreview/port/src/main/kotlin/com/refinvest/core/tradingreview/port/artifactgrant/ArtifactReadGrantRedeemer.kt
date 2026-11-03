package com.refinvest.core.tradingreview.port.artifactgrant

import com.refinvest.core.tradingreview.domain.*

interface ArtifactReadGrantRedeemer { fun redeem(token: String): ArtifactRedemption }
