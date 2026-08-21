package com.refinvest.core.auth.application.auth.logout

import com.refinvest.core.auth.port.inbound.auth.logout.LogoutCommand
import com.refinvest.core.auth.port.inbound.auth.logout.LogoutUseCase
import com.refinvest.core.auth.port.outbound.RefreshSessionStore
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
