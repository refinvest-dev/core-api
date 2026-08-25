package com.refinvest.core.member.port.inbound.get

fun interface GetMemberUseCase {
    fun execute(query: GetMemberQuery): GetMemberResult?
}
