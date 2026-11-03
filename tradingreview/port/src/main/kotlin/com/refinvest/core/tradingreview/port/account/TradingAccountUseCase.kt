package com.refinvest.core.tradingreview.port.account

import com.refinvest.core.tradingreview.port.pagination.TradingPage

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import java.time.Instant

interface TradingAccountUseCase {
    fun create(venue: TradingVenue, displayName: String, key: String): TradingAccount
    fun get(id: TradingAccountId): TradingAccount
    fun list(page: Int, size: Int): TradingPage<TradingAccount>
    fun delete(id: TradingAccountId, key: String): TradingDeletion
}
