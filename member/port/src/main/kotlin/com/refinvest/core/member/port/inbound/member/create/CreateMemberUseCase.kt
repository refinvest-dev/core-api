package com.refinvest.core.member.port.inbound.member.create

fun interface CreateMemberUseCase {
    fun execute(command: CreateMemberCommand): CreateMemberResult
}
