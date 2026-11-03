package com.refinvest.core.tradingreview.port.artifactstorage

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import java.io.InputStream
import java.time.Instant

data class StagedRawArtifact(val stagingKey: String, val sha256: String, val byteSize: Long,
                             val wrappedDek: ByteArray, val keyVersion: String,
                             val publicationKey: String? = null)
