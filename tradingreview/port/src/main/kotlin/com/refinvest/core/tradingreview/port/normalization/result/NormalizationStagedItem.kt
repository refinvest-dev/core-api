package com.refinvest.core.tradingreview.port.normalization.result

data class NormalizationStagedItem(
    val sectionType: String,
    val identity: String,
    val contentHash: String,
    val publicJson: String,
)
