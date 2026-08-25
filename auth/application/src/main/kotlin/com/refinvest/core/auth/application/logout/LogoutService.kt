package com.refinvest.core.auth.application.logout

import com.refinvest.core.auth.port.inbound.logout.LogoutCommand
import com.refinvest.core.auth.port.inbound.logout.LogoutUseCase
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
open class LogoutService(
    private val refreshSessionStore: RefreshSessionStore,
    private val clock: Clock,
) : LogoutUseCase {
    @Transactional
    override fun execute(command: LogoutCommand) {
        refreshSessionStore.revokeFamily(command.familyId, clock.instant())
    }
}
