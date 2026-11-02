package com.refinvest.core.auth.application.logout

import com.refinvest.core.auth.port.inbound.logout.LogoutCommand
import com.refinvest.core.auth.port.inbound.logout.LogoutUseCase
import com.refinvest.core.auth.port.outbound.token.RefreshTokenParser
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import com.refinvest.core.auth.port.outbound.persistence.RefreshFamilyRevocation
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Service
open class LogoutService(
    private val refreshSessionStore: RefreshSessionStore,
    private val clock: Clock,
    private val refreshTokenParser: RefreshTokenParser,
    private val transactionTemplate: TransactionTemplate,
) : LogoutUseCase {
    override fun execute(command: LogoutCommand) {
        val claims = command.rawRefreshToken?.let(refreshTokenParser::parse) ?: return
        transactionTemplate.executeWithoutResult {
            refreshSessionStore.revokeFamily(RefreshFamilyRevocation(claims.familyId, clock.instant()))
        }
    }
}
