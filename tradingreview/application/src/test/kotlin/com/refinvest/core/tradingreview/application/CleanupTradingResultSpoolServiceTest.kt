package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactLease
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactWorkspaces
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingResultSpoolCleanup
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.springframework.transaction.support.TransactionSynchronizationManager

class CleanupTradingResultSpoolServiceTest {
    private val now = Instant.parse("2026-11-03T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `cleanup shares the same cutoff and preserves spool then bounded workspace order`() {
        val calls = mutableListOf<String>()
        val cutoff = now.minus(Duration.ofHours(24))
        val spool = TradingResultSpoolCleanup {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertEquals(cutoff, it)
            calls += "spool"
            2
        }
        val workspaces = workspaces { time, limit ->
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertEquals(cutoff, time)
            assertEquals(100, limit)
            calls += "workspace"
            3
        }

        assertEquals(5, CleanupTradingResultSpoolService(clock, spool, workspaces, Duration.ofHours(24)).cleanup())
        assertEquals(listOf("spool", "workspace"), calls)
    }

    @Test
    fun `invalid retention is rejected before file cleanup`() {
        var calls = 0
        val spool = TradingResultSpoolCleanup { calls++; 0 }
        val workspaces = workspaces { _, _ -> calls++; 0 }
        for (retention in listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(24).plusSeconds(1))) {
            assertFailsWith<IllegalArgumentException> {
                CleanupTradingResultSpoolService(clock, spool, workspaces, retention)
            }
        }
        assertEquals(0, calls)
    }

    private fun workspaces(cleanup: (Instant, Int) -> Int) = object : TradingArtifactWorkspaces {
        override fun acquire(scope: TradingArtifactScope): TradingArtifactLease = error("Unexpected acquire")
        override fun purge(scope: TradingArtifactScope): Boolean = error("Unexpected purge")
        override fun cleanupOrphans(olderThan: Instant, limit: Int): Int = cleanup(olderThan, limit)
    }
}
