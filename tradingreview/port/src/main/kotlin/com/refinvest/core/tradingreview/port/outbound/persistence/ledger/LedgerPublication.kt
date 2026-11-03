package com.refinvest.core.tradingreview.port.outbound.persistence.ledger

import com.refinvest.core.tradingreview.port.outbound.compute.result.NormalizationResultDescriptor
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationDispatchLease

import com.refinvest.core.tradingreview.domain.LedgerRevision
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch

data class LedgerPublication(
    val revision: LedgerRevision,
    val run: NormalizationRun,
    val dispatch: NormalizationDispatch,
    val descriptor: NormalizationResultDescriptor,
    val lease: NormalizationDispatchLease,
)
