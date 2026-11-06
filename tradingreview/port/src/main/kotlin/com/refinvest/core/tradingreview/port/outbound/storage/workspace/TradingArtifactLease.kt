package com.refinvest.core.tradingreview.port.outbound.storage.workspace

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

interface TradingArtifactLease : AutoCloseable {
    fun checkActive()
    fun createTemporary(): TradingTemporaryArtifact
}
