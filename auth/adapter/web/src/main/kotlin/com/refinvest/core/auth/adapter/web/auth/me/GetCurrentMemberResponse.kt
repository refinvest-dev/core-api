package com.refinvest.core.auth.adapter.web.auth.me

import com.refinvest.core.auth.port.inbound.auth.me.GetCurrentMemberResult

data class GetCurrentMemberResponse(
    val memberId: String,
    val role: String,
) {
    companion object {
        fun from(result: GetCurrentMemberResult): GetCurrentMemberResponse = GetCurrentMemberResponse(
            memberId = result.memberId.value.toString(),
            role = result.role,
        )
    }
}
