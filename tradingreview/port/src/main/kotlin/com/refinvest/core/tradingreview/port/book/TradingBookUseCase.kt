package com.refinvest.core.tradingreview.port.book

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

interface TradingBookUseCase {
    fun create(accountId: TradingAccountId, displayName: String, productFamily: TradingProductFamily,
               positionMode: TradingPositionMode, settlementAsset: TradingSettlementAsset,
               reviewTimezone: String, key: String): TradingBook
    fun get(id: TradingBookId): TradingBook
    fun list(accountId: TradingAccountId, page: Int, size: Int): TradingPage<TradingBook>
    fun delete(id: TradingBookId, key: String): TradingDeletion
}
