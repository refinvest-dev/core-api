package com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem

import java.io.InputStream

data class RedeemTradingArtifactGrantResult(val input: InputStream, val contentLength: Long, val mediaType: String)
