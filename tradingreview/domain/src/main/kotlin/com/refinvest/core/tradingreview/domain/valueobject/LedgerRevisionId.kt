package com.refinvest.core.tradingreview.domain.valueobject

import com.refinvest.core.common.domain.Identifier
import java.util.UUID

@JvmInline
value class LedgerRevisionId(override val value: UUID) : Identifier<UUID>
