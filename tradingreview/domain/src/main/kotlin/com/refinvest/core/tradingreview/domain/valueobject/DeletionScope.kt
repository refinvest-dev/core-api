package com.refinvest.core.tradingreview.domain.valueobject

import com.refinvest.core.common.domain.Identifier

/** Scope semantics and typed target are inseparable. Historical scope labels are preserved on restore. */
class DeletionScope private constructor(
    val targetType: DeletionTargetType,
    val target: Identifier<Long>,
    val requestedScope: String,
) {
    init {
        require(target.value > 0)
        require(when (targetType) {
            DeletionTargetType.TRADING_ACCOUNT -> target is TradingAccountId && requestedScope == "ACCOUNT_SUBTREE"
            DeletionTargetType.TRADING_BOOK -> target is TradingBookId && requestedScope == "BOOK_SUBTREE"
            DeletionTargetType.TRADING_IMPORT_SESSION -> target is TradingImportSessionId &&
                requestedScope in setOf("IMPORT_SESSION_SUBTREE", "IMPORT_SUBTREE")
            DeletionTargetType.TRADING_SOURCE_ARTIFACT -> target is TradingSourceArtifactId && requestedScope == "RAW_ONLY"
        }) { "Deletion target and requested scope disagree" }
    }
    override fun equals(other: Any?): Boolean = other is DeletionScope &&
        targetType == other.targetType && target == other.target && requestedScope == other.requestedScope
    override fun hashCode(): Int = 31 * (31 * targetType.hashCode() + target.hashCode()) + requestedScope.hashCode()
    companion object {
        fun account(id: TradingAccountId) = DeletionScope(DeletionTargetType.TRADING_ACCOUNT, id, "ACCOUNT_SUBTREE")
        fun book(id: TradingBookId) = DeletionScope(DeletionTargetType.TRADING_BOOK, id, "BOOK_SUBTREE")
        fun importSession(id: TradingImportSessionId) = DeletionScope(DeletionTargetType.TRADING_IMPORT_SESSION, id, "IMPORT_SESSION_SUBTREE")
        fun rawArtifact(id: TradingSourceArtifactId) = DeletionScope(DeletionTargetType.TRADING_SOURCE_ARTIFACT, id, "RAW_ONLY")
        fun restore(type: DeletionTargetType, target: Identifier<Long>, requestedScope: String) =
            DeletionScope(type, target, requestedScope)
    }
}
