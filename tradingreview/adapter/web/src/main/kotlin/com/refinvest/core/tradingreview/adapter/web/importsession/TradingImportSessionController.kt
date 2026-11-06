package com.refinvest.core.tradingreview.adapter.web.importsession

import com.refinvest.core.tradingreview.adapter.web.artifact.TradingSourceArtifactResponse
import com.refinvest.core.tradingreview.adapter.web.deletion.TradingDeletionResponse
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.importsession.delete.DeleteTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.delete.DeleteTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionQuery
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.TradingUploadMetadata
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadTradingImportArtifactCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadTradingImportArtifactUseCase
import com.refinvest.core.tradingreview.port.inbound.normalization.retry.RetryTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.normalization.retry.RetryTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.normalization.validate.ValidateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.normalization.validate.ValidateTradingImportSessionUseCase
import jakarta.servlet.http.HttpServletRequest
import java.io.IOException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/trading-import-sessions")
class TradingImportSessionController(
    private val getImport: GetTradingImportSessionUseCase,
    private val validateImport: ValidateTradingImportSessionUseCase,
    private val retryImport: RetryTradingImportSessionUseCase,
    private val deleteImport: DeleteTradingImportSessionUseCase,
    private val uploadArtifact: UploadTradingImportArtifactUseCase,
    private val mapper: ObjectMapper,
) {
    @GetMapping("/{importSessionId}")
    fun get(@PathVariable importSessionId: Long) = TradingImportSessionResponse.from(
        getImport.execute(GetTradingImportSessionQuery(TradingImportSessionId(importSessionId))), mapper)

    @PostMapping("/{importSessionId}/validation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun validate(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String,
                 @RequestBody request: JsonNode): TradingImportSessionResponse {
        if (!request.isObject || request.size() > 1 ||
            (request.size() == 1 && !request.has("targetVersionSet")))
            throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        validateImport.execute(ValidateTradingImportSessionCommand(TradingImportSessionId(importSessionId), request.path("targetVersionSet").takeIf { !it.isMissingNode && !it.isNull }?.toString(), key))
        return TradingImportSessionResponse.from(getImport.execute(GetTradingImportSessionQuery(TradingImportSessionId(importSessionId))), mapper)
    }

    @PostMapping("/{importSessionId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retry(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String):
        TradingImportSessionResponse {
        retryImport.execute(RetryTradingImportSessionCommand(TradingImportSessionId(importSessionId), key))
        return TradingImportSessionResponse.from(getImport.execute(GetTradingImportSessionQuery(TradingImportSessionId(importSessionId))), mapper)
    }

    @DeleteMapping("/{importSessionId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(deleteImport.execute(DeleteTradingImportSessionCommand(TradingImportSessionId(importSessionId), key)))

    @PostMapping("/{importSessionId}/artifacts", consumes = ["multipart/form-data"])
    fun upload(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String,
               request: HttpServletRequest): ResponseEntity<TradingSourceArtifactResponse> {
        try {
            val reader = StreamingMultipartReader(request.inputStream, request.contentType)
            val first = reader.nextPart() ?: throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            var metadata: TradingUploadMetadata? = null
            val file = if (first.name == "metadata") {
                metadata = readMetadata(first)
                reader.nextPart() ?: throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            } else first
            if (file.name != "file") throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            val result = uploadArtifact.execute(UploadTradingImportArtifactCommand(TradingImportSessionId(importSessionId), file.body, key, metadata = {
                val parsed = metadata ?: (reader.nextPart() ?: throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST))
                    .let(::readMetadata)
                if (reader.nextPart() != null) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
                if (file.contentType?.substringBefore(';') != parsed.contentType)
                    throw TradingFailure("UNSUPPORTED_MEDIA_TYPE", TradingFailureKind.UNSUPPORTED_MEDIA)
                parsed
            }))
            return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
                .body(TradingSourceArtifactResponse.from(result.artifact))
        } catch (failure: TradingFailure) { throw failure }
          catch (_: IOException) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
          catch (_: IllegalArgumentException) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
          catch (_: IllegalStateException) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
          catch (_: Exception) { throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE) }
    }

    private fun readMetadata(part: StreamingMultipartReader.Part): TradingUploadMetadata {
        if (part.name != "metadata" || part.contentType?.substringBefore(';') != "application/json")
            throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val bytes = part.body.use { it.readNBytes(8_193) }
        if (bytes.size > 8_192) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val json = try { mapper.readTree(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
        return parseMetadata(json)
    }

    private fun parseMetadata(node: JsonNode): TradingUploadMetadata {
        if (!node.isObject) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val role = try { TradingArtifactRole.valueOf(node.path("role").asText()) }
            catch (_: Exception) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
        val filename = node.path("filename").asText()
        val contentType = node.path("contentType").asText()
        val size = node.path("byteSize").asLong()
        val sha = node.path("sha256").asText()
        val dialect = node.path("dialectCandidate").asText()
        if (node.size() != 6 || filename.isBlank() || size <= 0 || sha.isBlank() || dialect.isBlank())
            throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        return TradingUploadMetadata(role, filename, contentType, size, sha, dialect)
    }
}
