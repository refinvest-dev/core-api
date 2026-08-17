package com.refinvest.core.strategy.domain.policy

data object PositionPolicy {
    const val longOnly: Boolean = true
    const val singlePosition: Boolean = true
    const val duplicateEntry: String = "IGNORE"
}
