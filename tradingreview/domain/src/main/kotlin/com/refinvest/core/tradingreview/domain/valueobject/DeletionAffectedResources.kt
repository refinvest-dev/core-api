package com.refinvest.core.tradingreview.domain.valueobject

/** Existing admission-time resource counters, not proof of completed purge. */
data class DeletionAffectedResources(val accounts: Long, val books: Long,
                                     val importSessions: Long = 0, val artifacts: Long = 0) {
    init { require(listOf(accounts, books, importSessions, artifacts).all { it >= 0 }) }
    internal fun requireTarget(scope: DeletionScope) {
        require(when (scope.targetType) {
            DeletionTargetType.TRADING_ACCOUNT -> accounts == 1L
            DeletionTargetType.TRADING_BOOK -> accounts == 0L && books == 1L
            DeletionTargetType.TRADING_IMPORT_SESSION -> accounts == 0L && books == 0L && importSessions == 1L
            DeletionTargetType.TRADING_SOURCE_ARTIFACT -> accounts == 0L && books == 0L && importSessions == 0L && artifacts == 1L
        }) { "Affected resource counters do not describe the requested target" }
    }
}
