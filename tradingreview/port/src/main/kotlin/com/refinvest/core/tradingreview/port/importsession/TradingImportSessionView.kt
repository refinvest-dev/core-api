package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.tradingreview.port.ledger.PublishedLedgerSummary

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import java.io.InputStream
import java.time.Instant

data class TradingImportSessionView(val session: TradingImportSession, val artifacts: List<TradingSourceArtifact>,
                                    val published: PublishedLedgerSummary? = null)
