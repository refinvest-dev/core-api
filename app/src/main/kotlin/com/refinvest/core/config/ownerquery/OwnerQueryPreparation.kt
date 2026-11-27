package com.refinvest.core.config.ownerquery

import com.refinvest.core.auth.application.authority.AuthenticateAuthorityCallerService
import com.refinvest.core.auth.application.authority.CheckMemberAuthorityService
import com.refinvest.core.auth.application.delegation.IssueOwnerQueryDelegationService
import com.refinvest.core.auth.adapter.out.persistence.JdbcMemberAuthorityReader
import com.refinvest.core.auth.port.inbound.authority.AuthenticateAuthorityCallerUseCase
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import com.refinvest.core.auth.port.inbound.delegation.IssueOwnerQueryDelegationUseCase
import com.refinvest.core.auth.port.outbound.member.CurrentMemberIdProvider
import com.refinvest.core.tradingreview.adapter.reviewapi.OwnerMetadataHttpClient
import com.refinvest.core.tradingreview.adapter.reviewapi.OwnerMetadataHttpOptions
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class PreparedOwnerQuery internal constructor(
    val issuer: IssueOwnerQueryDelegationUseCase,
    val authority: CheckMemberAuthorityUseCase,
    val authorityCaller: AuthenticateAuthorityCallerUseCase,
    val reader: OwnerMetadataReader,
    val requestTimeout: Duration,
    private val closeClient: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    /** Caller must stop admission and drain users before closing this owner. No datasource ownership. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                closeClient()
            } catch (_: Exception) {
                throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.CLEANUP)
            }
        }
    }

    override fun toString(): String = "PreparedOwnerQuery(redacted)"
}

/** Explicit call only. No component, Bean, environment access, DB probe or listener registration. */
object OwnerQueryPreparation {
    fun prepare(path: Path, primary: DataSource, member: CurrentMemberIdProvider, clock: Clock, timeout: Duration): PreparedOwnerQuery =
        prepare(path, primary, member, clock, timeout, System::nanoTime, ::OwnerMetadataHttpClient)

    internal fun prepare(
        path: Path,
        primary: DataSource,
        member: CurrentMemberIdProvider,
        clock: Clock,
        timeout: Duration,
        nanoTime: () -> Long,
        createClient: (OwnerMetadataHttpOptions) -> OwnerMetadataHttpClient,
    ): PreparedOwnerQuery {
        val budget = PreparationBudget(timeout, nanoTime)
        val bytes = OwnerQuerySnapshotFile.read(path, budget)
        val snapshot = try {
            OwnerQuerySnapshot.decode(bytes, budget)
        } finally {
            bytes.fill(0)
        }
        var client: OwnerMetadataHttpClient? = null
        try {
            budget.check()
            val issuer = IssueOwnerQueryDelegationService(member, snapshot.signer, clock)
            val authority = CheckMemberAuthorityService(snapshot.verifier, JdbcMemberAuthorityReader(primary, snapshot.queryTimeoutSeconds), clock)
            val caller = AuthenticateAuthorityCallerService(snapshot.authorityCaller)
            client = createClient(snapshot.clientOptions)
            budget.check()
            val owned = client
            return PreparedOwnerQuery(issuer, authority, caller, owned, snapshot.requestTimeout) { owned.close() }
        } catch (error: Exception) {
            try {
                client?.close()
            } catch (_: Exception) {
                throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.CLEANUP)
            }
            if (error is OwnerQueryPreparationException) throw OwnerQueryPreparationException(error.stage)
            throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.CAPABILITIES)
        }
    }
}
