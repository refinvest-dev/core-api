package com.refinvest.core.tradingreview.port.outbound.ownerquery

import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataCall
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResource
import java.time.Duration

fun interface OwnerMetadataReader {
    fun read(query: Request, delegation: OwnerQueryDelegation, budget: Duration): OwnerMetadataCall

    data class Request(val resource: OwnerMetadataResource, val resourceId: String, val traceParent: String?, val traceState: String?)
}
