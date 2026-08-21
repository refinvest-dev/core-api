package com.refinvest.core.shared.kernel.member

import com.refinvest.core.common.domain.Identifier

@JvmInline
value class MemberId(override val value: Long) : Identifier<Long>
