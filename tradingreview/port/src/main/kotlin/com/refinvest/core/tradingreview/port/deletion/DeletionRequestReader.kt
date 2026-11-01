package com.refinvest.core.tradingreview.port.deletion

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId

interface DeletionRequestReader { fun find(id: DeletionRequestId, owner: MemberId): TradingDeletion? }
