package com.refinvest.core.tradingreview.port.outbound.ownerquery

fun interface OwnerQueryDelegationIssuer {
    fun issue(): OwnerQueryDelegation
}
