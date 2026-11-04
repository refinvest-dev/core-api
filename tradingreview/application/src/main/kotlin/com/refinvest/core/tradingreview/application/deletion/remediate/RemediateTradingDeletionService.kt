package com.refinvest.core.tradingreview.application.deletion.remediate

import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.inbound.deletion.remediate.RemediateTradingDeletionCommand
import com.refinvest.core.tradingreview.port.inbound.deletion.remediate.RemediateTradingDeletionUseCase
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRemediation
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.TradingDeletionRemediation
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class RemediateTradingDeletionService(
    private val remediation: TradingDeletionRemediation,
    private val ids: TradingIdGenerator,
    private val clock: Clock,
) : RemediateTradingDeletionUseCase {
    @Transactional
    override fun execute(command: RemediateTradingDeletionCommand): TradingDeletion =
        TradingDeletion.fromDomain(remediation.remediate(DeletionRemediation(command.failedId, ids.deletionId(), clock.instant())))
}
