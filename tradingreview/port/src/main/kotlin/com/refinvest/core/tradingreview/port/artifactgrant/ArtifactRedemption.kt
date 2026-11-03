package com.refinvest.core.tradingreview.port.artifactgrant

import com.refinvest.core.tradingreview.domain.*
import java.io.InputStream

data class ArtifactRedemption(val input: InputStream, val contentLength: Long, val mediaType: String)
