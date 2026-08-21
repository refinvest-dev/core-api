package com.refinvest.core.member.port.inbound.member.get

fun interface GetMemberUseCase {
    fun execute(query: GetMemberQuery): GetMemberResult?
}
