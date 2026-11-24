package com.refinvest.core.tradingreview.port.inbound.ownerquery

import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataCall

fun interface ProxyOwnerMetadataUseCase {
    fun execute(query: OwnerMetadataQuery): OwnerMetadataCall
}
