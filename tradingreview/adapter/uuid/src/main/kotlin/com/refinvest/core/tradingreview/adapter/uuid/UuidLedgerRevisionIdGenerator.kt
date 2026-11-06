package com.refinvest.core.tradingreview.adapter.uuid

import com.refinvest.core.tradingreview.port.outbound.identity.LedgerRevisionIdGenerator

import com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class UuidLedgerRevisionIdGenerator : LedgerRevisionIdGenerator {
    override fun revisionId() = LedgerRevisionId(UUID.randomUUID())
}
