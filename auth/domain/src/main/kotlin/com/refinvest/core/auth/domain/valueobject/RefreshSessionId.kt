package com.refinvest.core.auth.domain.valueobject

import com.refinvest.core.common.domain.Identifier
import java.util.UUID

@JvmInline
value class RefreshSessionId(override val value: UUID) : Identifier<UUID>
