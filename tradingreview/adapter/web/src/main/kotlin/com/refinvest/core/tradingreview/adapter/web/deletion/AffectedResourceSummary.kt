package com.refinvest.core.tradingreview.adapter.web.deletion



data class AffectedResourceSummary(val accounts: Long, val books: Long, val importSessions: Long = 0,
                                   val artifacts: Long = 0, val ledgerRevisions: Long = 0,
                                   val analysisRuns: Long = 0, val reprocessingRuns: Long = 0,
                                   val snapshotStatus: String = "LEGACY_PARTIAL")
