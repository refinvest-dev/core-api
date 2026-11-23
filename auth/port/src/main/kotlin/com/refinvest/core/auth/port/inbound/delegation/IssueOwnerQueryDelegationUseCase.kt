package com.refinvest.core.auth.port.inbound.delegation

fun interface IssueOwnerQueryDelegationUseCase {
    fun execute(): OwnerQueryDelegationResult
}
