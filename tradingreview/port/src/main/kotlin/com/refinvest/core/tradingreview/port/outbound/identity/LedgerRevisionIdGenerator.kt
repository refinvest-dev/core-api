package com.refinvest.core.tradingreview.port.outbound.identity

import com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId

interface LedgerRevisionIdGenerator { fun revisionId(): LedgerRevisionId }
