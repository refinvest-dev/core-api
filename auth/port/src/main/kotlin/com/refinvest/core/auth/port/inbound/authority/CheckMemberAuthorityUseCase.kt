package com.refinvest.core.auth.port.inbound.authority

fun interface CheckMemberAuthorityUseCase {
    fun execute(command: CheckMemberAuthorityCommand): MemberAuthorityResult
}

class CheckMemberAuthorityCommand(val delegation: String, val expectedActor: String, val checkId: String) {
    override fun toString(): String = "CheckMemberAuthorityCommand(redacted)"
}
