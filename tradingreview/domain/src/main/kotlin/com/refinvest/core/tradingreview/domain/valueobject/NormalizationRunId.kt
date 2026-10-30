package com.refinvest.core.tradingreview.domain.valueobject

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import com.refinvest.core.common.domain.Identifier
import java.util.UUID

@JvmInline
value class NormalizationRunId(override val value: String) : Identifier<String> {
    init { require(UUID.fromString(value).toString() == value) { "Invalid normalization run ID" } }
}
