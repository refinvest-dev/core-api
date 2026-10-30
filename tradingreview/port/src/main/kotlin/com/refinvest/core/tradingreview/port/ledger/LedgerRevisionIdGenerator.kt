package com.refinvest.core.tradingreview.port.ledger

import com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId

interface LedgerRevisionIdGenerator { fun revisionId(): LedgerRevisionId }
