package com.refinvest.core.tradingreview.port.analytics.input

import java.io.OutputStream
import java.io.InputStream

data class AnalyticsInputArtifact(
    val coreReferenceId: String,
    val mediaType: String,
    val byteSize: Long,
    val sha256: String,
)
