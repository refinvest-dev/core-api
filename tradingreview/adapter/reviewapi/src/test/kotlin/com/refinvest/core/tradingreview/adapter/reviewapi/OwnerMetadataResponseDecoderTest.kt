package com.refinvest.core.tradingreview.adapter.reviewapi

import com.refinvest.core.tradingreview.port.common.ownerquery.*
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import kotlin.test.*

class OwnerMetadataResponseDecoderTest {
    private val decoder=OwnerMetadataResponseDecoder()
    private val query=OwnerMetadataReader.Request(OwnerMetadataResource.ACCOUNT,"9007199254740993",null,null)
    private val body="""{"id":"9007199254740993","venue":"BINANCE","displayName":"name","status":"ACTIVE","createdAt":"2026-11-24T00:00:00Z"}"""

    @Test fun `strict response rejects ambiguity repair coercion and unknown fields`() {
        for(raw in listOf(
            body.dropLast(1)+",\"extra\":1}", body.replace("\"id\":","\"ID\":"),
            body.replace("\"9007199254740993\"","9007199254740993"),
            body.replace("\"name\"","\"\\uD800\""),body+"{}",
            body.dropLast(1)+",\"\\u0069d\":\"9007199254740993\"}",
            body.replace("ACTIVE","DELETION_PENDING"),body.replace("00:00:00Z","00:00:00+09:00"))) {
            assertEquals(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR,
                assertIs<OwnerMetadataResult.Failure>(decoder.decode(200,raw.toByteArray(),query,"trace")).kind)
        }
    }

    @Test fun `legitimate replacement character and surrogate pair are preserved`() {
        for(name in listOf("�","😀","\\uD83D\\uDE00")) {
            assertIs<OwnerMetadataResult.Account>(decoder.decode(200,body.replace("name",name).toByteArray(),query,"trace"))
        }
    }

    @Test fun `error trace retryability and status must match the approved registry`() {
        val raw="""{"code":"RESOURCE_NOT_FOUND","message":"raw secret","traceId":"trace","retryable":false}"""
        assertEquals(OwnerMetadataFailure.RESOURCE_NOT_FOUND,assertIs<OwnerMetadataResult.Failure>(decoder.decode(404,raw.toByteArray(),query,"trace")).kind)
        for((status,value) in listOf(403 to raw,404 to raw.replace("false","true"),404 to raw.replace("\"trace\"","\"other\""))) {
            assertEquals(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR,assertIs<OwnerMetadataResult.Failure>(decoder.decode(status,value.toByteArray(),query,"trace")).kind)
        }
    }
}
