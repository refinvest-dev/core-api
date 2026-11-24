package com.refinvest.core.tradingreview.adapter.reviewapi

import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataFailure
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResource
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResult
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

internal class OwnerMetadataResponseDecoder {
    private val json = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(2)
                .maxStringLength(16384)
                .maxNameLength(64)
                .maxTokenCount(128)
                .build(),
        )
        .build()

    fun checkHeaders(info: HttpResponse.ResponseInfo) {
        val headers = info.headers()
        val size = headers.map().entries.sumOf { (key, values) -> values.sumOf { key.length.toLong() + it.length } }
        if (size > 8192 || info.statusCode() !in setOf(200, 400, 403, 404, 410, 503, 504) ||
            headers.allValues("Cache-Control") != listOf("no-store") || headers.allValues("Content-Type").size != 1 ||
            !contentType.matches(headers.firstValue("Content-Type").orElse("")) ||
            listOf("Content-Encoding", "Location", "WWW-Authenticate", "Set-Cookie").any { headers.allValues(it).isNotEmpty() } ||
            headers.firstValueAsLong("Content-Length").orElse(0) > 16384
        ) {
            invalid()
        }
    }

    fun decode(
        status: Int,
        body: ByteArray,
        query: OwnerMetadataReader.Request,
        traceId: String,
    ): OwnerMetadataResult = try {
        val fields = if (status != 200) errorFields else if (query.resource == OwnerMetadataResource.ACCOUNT) accountFields else bookFields
        val values = objectValue(body, fields)

        if (status != 200) {
            val code = values["code"] as String
            val mapping = errorMapping[status to code] ?: invalid()
            if (values["traceId"] != traceId || values["retryable"] != mapping.second) invalid()

            OwnerMetadataResult.Failure(mapping.first)
        } else {
            if (values["id"] != query.resourceId || values["venue"] != "BINANCE" || values["status"] !in setOf("ACTIVE", "ARCHIVED")) invalid()

            val created = values["createdAt"] as String
            if (!created.endsWith('Z')) invalid()
            val instant = Instant.parse(created)

            if (query.resource == OwnerMetadataResource.ACCOUNT) {
                OwnerMetadataResult.Account(
                    values["id"] as String, "BINANCE", values["displayName"] as String, values["status"] as String, instant,
                )
            } else {
                val account = values["tradingAccountId"] as String
                val id = account.toLongOrNull()
                if (id == null || id <= 0 || id.toString() != account || values["productFamily"] != "LINEAR_PERPETUAL" ||
                    values["positionMode"] != "ONE_WAY" || values["settlementAsset"] != "USDT"
                ) {
                    invalid()
                }

                OwnerMetadataResult.Book(
                    values["id"] as String, account, "BINANCE", "LINEAR_PERPETUAL", "ONE_WAY", "USDT",
                    values["reviewTimezone"] as String, values["status"] as String, instant,
                )
            }
        }
    } catch (_: Exception) {
        OwnerMetadataResult.Failure(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR)
    }

    private fun objectValue(bytes: ByteArray, expected: Set<String>): Map<String, Any> {
        if (bytes.size !in 1..16384) invalid()

        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

        return json.createParser(text).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) invalid()

            val values = linkedMapOf<String, Any>()
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) invalid()
                val name = parser.currentName()
                if (name !in expected || name in values) invalid()

                val token = parser.nextToken()
                values[name] = if (name == "retryable") {
                    if (token !in listOf(JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE)) invalid()
                    token == JsonToken.VALUE_TRUE
                } else {
                    if (token != JsonToken.VALUE_STRING) invalid()
                    parser.getString().also(::validateText)
                }
            }

            if (values.keys != expected || parser.nextToken() != null) invalid()
            values
        }
    }

    private fun validateText(value: String) {
        if (value.length > 16384) invalid()

        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (Character.isLowSurrogate(c)) invalid()
            if (Character.isHighSurrogate(c)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) invalid()
            }
        }
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid owner metadata response")

    private companion object {
        val contentType = Regex("""application/json(?:\s*;\s*charset=(?:utf-8|"utf-8"))?""", RegexOption.IGNORE_CASE)
        val accountFields = setOf("id", "venue", "displayName", "status", "createdAt")
        val bookFields = setOf("id", "tradingAccountId", "venue", "productFamily", "positionMode", "settlementAsset", "reviewTimezone", "status", "createdAt")
        val errorFields = setOf("code", "message", "traceId", "retryable")
        val errorMapping = mapOf(
            (400 to "INVALID_REQUEST") to (OwnerMetadataFailure.INVALID_REQUEST to false),
            (403 to "CALLER_NOT_ALLOWED") to (OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR to false),
            (403 to "MEMBER_ACCESS_DENIED") to (OwnerMetadataFailure.MEMBER_ACCESS_DENIED to false),
            (404 to "RESOURCE_NOT_FOUND") to (OwnerMetadataFailure.RESOURCE_NOT_FOUND to false),
            (410 to "RESOURCE_DELETED") to (OwnerMetadataFailure.RESOURCE_DELETED to false),
            (503 to "AUTHORITY_UNAVAILABLE") to (OwnerMetadataFailure.AUTHORITY_UNAVAILABLE to true),
            (503 to "OWNER_QUERY_UNAVAILABLE") to (OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE to true),
            (504 to "REQUEST_DEADLINE_EXCEEDED") to (OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED to true),
        )
    }
}
