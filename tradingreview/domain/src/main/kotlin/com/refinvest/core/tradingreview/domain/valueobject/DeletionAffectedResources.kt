package com.refinvest.core.tradingreview.domain.valueobject

/** Immutable request-time scope inventory, never proof of completed purge. Legacy provenance is explicit. */
data class DeletionAffectedResources(val accounts: Long, val books: Long,
                                     val importSessions: Long = 0, val artifacts: Long = 0,
                                     val ledgerRevisions: Long = 0, val analysisRuns: Long = 0,
                                     val reprocessingRuns: Long = 0,
                                     val snapshotStatus: DeletionCountSnapshotStatus = DeletionCountSnapshotStatus.REQUEST_TIME_SNAPSHOT) {
    init { require(listOf(accounts, books, importSessions, artifacts, ledgerRevisions, analysisRuns, reprocessingRuns).all { it >= 0 }) }
    internal fun requireTarget(scope: DeletionScope) {
        require(when (scope.targetType) {
            DeletionTargetType.TRADING_ACCOUNT -> accounts == 1L
            DeletionTargetType.TRADING_BOOK -> accounts == 0L && books == 1L
            DeletionTargetType.TRADING_IMPORT_SESSION -> accounts == 0L && books == 0L && importSessions == 1L
            DeletionTargetType.TRADING_SOURCE_ARTIFACT -> accounts == 0L && books == 0L && importSessions == 0L && artifacts == 1L &&
                ledgerRevisions == 0L && analysisRuns == 0L && reprocessingRuns == 0L
        }) { "Affected resource counters do not describe the requested target" }
    }
}
