package com.refinvest.core.tradingreview.domain.valueobject

import com.refinvest.core.common.domain.Identifier

@JvmInline
value class DeletionRequestId(override val value: Long) : Identifier<Long>
