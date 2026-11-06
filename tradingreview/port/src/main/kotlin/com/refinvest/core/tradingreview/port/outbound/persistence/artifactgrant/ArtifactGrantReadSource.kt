package com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant

import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId

class ArtifactGrantReadSource(
    val id: TradingSourceArtifactId, val objectKey: String, wrappedDek: ByteArray,
    val keyVersion: String, val contentLength: Long, val mediaType: String,
) {
    private val key = wrappedDek.copyOf()
    val wrappedDek: ByteArray get() = key.copyOf()
    override fun toString() = "ArtifactGrantReadSource(id=$id)"
}
