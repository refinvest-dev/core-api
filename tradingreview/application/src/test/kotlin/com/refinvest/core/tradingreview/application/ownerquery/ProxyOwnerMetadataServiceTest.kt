package com.refinvest.core.tradingreview.application.ownerquery

import com.refinvest.core.tradingreview.port.common.ownerquery.*
import com.refinvest.core.tradingreview.port.inbound.ownerquery.OwnerMetadataQuery
import com.refinvest.core.tradingreview.port.outbound.ownerquery.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class ProxyOwnerMetadataServiceTest {
    private val now = Instant.parse("2026-11-24T00:00:00Z")
    private val query = OwnerMetadataQuery(OwnerMetadataResource.ACCOUNT,"9007199254740993",null,null)
    private val clock = Clock.fixed(now,ZoneOffset.UTC)

    @Test
    fun `signs on current thread and passes remaining budget without legacy access`() {
        val caller = Thread.currentThread()
        var tick=0L
        var issued=false
        val service=ProxyOwnerMetadataService(OwnerQueryDelegationIssuer {
            assertSame(caller,Thread.currentThread())
            issued=true
            tick=100_000_000
            OwnerQueryDelegation("secret",now.plusSeconds(60))
        }, OwnerMetadataReader { request, token, budget ->
            assertTrue(issued)
            assertEquals(query.resourceId,request.resourceId)
            assertEquals("secret",token.compactToken)
            assertEquals(Duration.ofMillis(900),budget)
            OwnerMetadataCall.completed(OwnerMetadataResult.Failure(OwnerMetadataFailure.RESOURCE_NOT_FOUND))
        },Duration.ofSeconds(1),clock,{tick})
        assertEquals(OwnerMetadataFailure.RESOURCE_NOT_FOUND,(service.execute(query).result.toCompletableFuture().join() as OwnerMetadataResult.Failure).kind)
    }

    @Test
    fun `bad IDs never issue delegation or call reader`() {
        val service=ProxyOwnerMetadataService(OwnerQueryDelegationIssuer { error("must not issue") },
            OwnerMetadataReader { _,_,_->error("must not read") },Duration.ofSeconds(1),clock)
        for(id in listOf("0","01","-1","9223372036854775808","1.0")) {
            val result=service.execute(query.copy(resourceId=id)).result.toCompletableFuture().join() as OwnerMetadataResult.Failure
            assertEquals(OwnerMetadataFailure.INVALID_REQUEST,result.kind)
        }
    }

    @Test
    fun `expired preparation and signer failure do not call reader`() {
        for(failedSigner in listOf(false,true)) {
            val service=ProxyOwnerMetadataService(OwnerQueryDelegationIssuer {
                if(failedSigner)throw OwnerQueryDelegationUnavailable()
                OwnerQueryDelegation("expired",now)
            },OwnerMetadataReader { _,_,_->error("must not read") },Duration.ofSeconds(1),clock)
            val result=service.execute(query).result.toCompletableFuture().join() as OwnerMetadataResult.Failure
            assertEquals(if(failedSigner)OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR else OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED,result.kind)
        }
    }

    @Test
    fun `cancellation handle stays attached to the asynchronous read`() {
        val result=CompletableFuture<OwnerMetadataResult>()
        val call=OwnerMetadataCall(result,Duration.ofSeconds(1)){result.cancel(true)}
        val service=ProxyOwnerMetadataService(OwnerQueryDelegationIssuer {OwnerQueryDelegation("secret",now.plusSeconds(60))},
            OwnerMetadataReader { _,_,_->call },Duration.ofSeconds(1),clock)
        assertSame(call,service.execute(query))
        call.cancel()
        assertTrue(result.isCancelled)
    }
}
