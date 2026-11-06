package com.refinvest.core.auth.domain.valueobject

import com.refinvest.core.auth.domain.RefreshSession

/** Validated by the current aggregate; persistence must atomically compare and store both resulting states. */
class RefreshSessionRotation internal constructor(
    val expected: RefreshSession,
    val rotated: RefreshSession,
    val replacement: RefreshSession,
)
