package com.refinvest.core.tradingreview.port.outbound.artifactgrant

import com.refinvest.core.tradingreview.domain.*
import java.io.InputStream

data class ArtifactRedemption(val input: InputStream, val contentLength: Long, val mediaType: String)
