package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.TradingNormalizationTransitionGate
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Semaphore

@Repository
class JdbcTradingNormalizationTransitionGate(private val jdbc: JdbcTemplate) : TradingNormalizationTransitionGate {
    // Reserve at most one connection per instance for the send fence. The fleet fence is PostgreSQL.
    private val sending = Semaphore(1)

    override fun requireAdmission() {
        if (!publicationAllowed()) throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503)
    }

    override fun publicationAllowed(): Boolean {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        jdbc.execute("SELECT pg_advisory_xact_lock_shared(hashtext(current_schema()), 28028)")
        return jdbc.queryForObject("SELECT paused FROM trading_normalization_transition WHERE singleton",
            Boolean::class.java) == false
    }

    override fun <T : Any> withCreatePermit(action: () -> T): T? {
        check(!TransactionSynchronizationManager.isActualTransactionActive())
        if (!sending.tryAcquire()) return null
        try {
            return jdbc.execute(ConnectionCallback<T?> { connection ->
                connection.createStatement().use {
                    it.execute("SELECT pg_advisory_lock_shared(hashtext(current_schema()), 28028)")
                }
                try {
                    val open = connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT paused FROM trading_normalization_transition WHERE singleton").use {
                            check(it.next())
                            !it.getBoolean(1)
                        }
                    }
                    if (open) action() else null
                } finally {
                    // Session advisory locks must never leak back into the connection pool.
                    try {
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT pg_advisory_unlock_shared(hashtext(current_schema()), 28028)").use {
                                check(it.next() && it.getBoolean(1))
                            }
                        }
                    } catch (error: Exception) {
                        connection.abort { command -> command.run() }
                        throw error
                    }
                }
            })
        } finally { sending.release() }
    }
}
