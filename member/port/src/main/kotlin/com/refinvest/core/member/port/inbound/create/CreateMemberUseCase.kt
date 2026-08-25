package com.refinvest.core.member.port.inbound.create

fun interface CreateMemberUseCase {
    fun execute(command: CreateMemberCommand): CreateMemberResult
}
