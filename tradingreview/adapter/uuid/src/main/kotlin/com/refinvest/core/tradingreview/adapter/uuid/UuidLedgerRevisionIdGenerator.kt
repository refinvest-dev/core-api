package com.refinvest.core.tradingreview.adapter.uuid

import com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId
import com.refinvest.core.tradingreview.port.ledger.LedgerRevisionIdGenerator
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class UuidLedgerRevisionIdGenerator : LedgerRevisionIdGenerator {
    override fun revisionId() = LedgerRevisionId(UUID.randomUUID())
}
