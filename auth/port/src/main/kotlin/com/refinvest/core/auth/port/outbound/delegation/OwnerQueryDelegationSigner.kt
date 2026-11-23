package com.refinvest.core.auth.port.outbound.delegation

fun interface OwnerQueryDelegationSigner {
    fun sign(payload: OwnerQueryDelegationPayload): String
}
