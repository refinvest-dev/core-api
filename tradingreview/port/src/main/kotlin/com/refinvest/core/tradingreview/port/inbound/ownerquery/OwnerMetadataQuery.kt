package com.refinvest.core.tradingreview.port.inbound.ownerquery

import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResource

data class OwnerMetadataQuery(
    val resource: OwnerMetadataResource,
    val resourceId: String,
    val traceParent: String?,
    val traceState: String?,
)
