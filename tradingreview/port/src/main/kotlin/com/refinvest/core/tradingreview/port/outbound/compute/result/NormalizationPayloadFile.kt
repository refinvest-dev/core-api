package com.refinvest.core.tradingreview.port.outbound.compute.result

import com.refinvest.core.tradingreview.domain.*
import java.io.InputStream

/** A temporary HTTP entity body. Production downloads use encrypted spool bytes. */
interface NormalizationPayloadFile : AutoCloseable {
    val byteSize: Long
    val sha256: String
    fun open(): InputStream
}
