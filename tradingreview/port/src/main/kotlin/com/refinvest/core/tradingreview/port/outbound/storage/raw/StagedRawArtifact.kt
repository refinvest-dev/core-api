package com.refinvest.core.tradingreview.port.outbound.storage.raw

import com.refinvest.core.tradingreview.domain.*

data class StagedRawArtifact(val stagingKey: String, val sha256: String, val byteSize: Long,
                             val wrappedDek: ByteArray, val keyVersion: String,
                             val publicationKey: String? = null)
