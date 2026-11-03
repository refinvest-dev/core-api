package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.tradingreview.port.importsession.upload.TradingUploadMetadata
import com.refinvest.core.tradingreview.port.importsession.upload.UploadArtifactResult
import com.refinvest.core.tradingreview.port.pagination.TradingPage

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

interface TradingImportUseCase {
    fun create(bookId: TradingBookId, sourceTimezone: String, reviewTimezone: String,
               mode: TradingPositionMode, dataPolicyVersion: String?, clientRequestReference: String?, key: String): TradingImportSession
    fun get(id: TradingImportSessionId): TradingImportSessionView
    fun list(bookId: TradingBookId, page: Int, size: Int): TradingPage<TradingImportSessionView>
    fun upload(id: TradingImportSessionId, role: TradingArtifactRole, filename: String,
               contentType: String, declaredSize: Long, declaredSha256: String,
               dialectCandidate: String, source: InputStream, key: String): UploadArtifactResult
    fun uploadDeferred(id: TradingImportSessionId, source: InputStream, key: String,
                       metadata: () -> TradingUploadMetadata): UploadArtifactResult
    fun delete(id: TradingImportSessionId, key: String): TradingDeletion
    fun deleteArtifact(id: TradingSourceArtifactId, key: String): TradingDeletion
}
