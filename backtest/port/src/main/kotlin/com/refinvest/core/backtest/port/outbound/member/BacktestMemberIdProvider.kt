package com.refinvest.core.backtest.port.outbound.member

import com.refinvest.core.shared.kernel.member.MemberId

fun interface BacktestMemberIdProvider {
    fun currentMemberId(): MemberId
}
