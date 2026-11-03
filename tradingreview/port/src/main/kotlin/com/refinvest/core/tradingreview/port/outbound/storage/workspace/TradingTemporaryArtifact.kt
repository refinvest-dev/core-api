package com.refinvest.core.tradingreview.port.outbound.storage.workspace

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

interface TradingTemporaryArtifact : AutoCloseable {
    fun openInput(): InputStream
    fun openOutput(): OutputStream
}
