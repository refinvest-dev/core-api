package com.refinvest.core.tradingreview.port.normalization.result

import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import java.time.Duration
import java.time.Instant

data class NormalizationStagedItem(
    val sectionType: String,
    val identity: String,
    val contentHash: String,
    val publicJson: String,
)
