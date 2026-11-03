package com.refinvest.core.tradingreview.port.member

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*

interface TradingMemberProvider { fun currentMemberId(): MemberId }
