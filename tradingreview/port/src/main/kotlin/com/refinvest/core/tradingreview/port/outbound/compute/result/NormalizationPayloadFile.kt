package com.refinvest.core.tradingreview.port.outbound.compute.result

import com.refinvest.core.tradingreview.domain.*
import java.io.InputStream

interface NormalizationPayloadFile : AutoCloseable {
    val byteSize: Long
    val sha256: String
    fun open(): InputStream
}
