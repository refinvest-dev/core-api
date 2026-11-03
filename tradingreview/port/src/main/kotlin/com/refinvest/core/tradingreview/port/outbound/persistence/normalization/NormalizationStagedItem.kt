package com.refinvest.core.tradingreview.port.outbound.persistence.normalization

data class NormalizationStagedItem(
    val sectionType: String,
    val identity: String,
    val contentHash: String,
    val publicJson: String,
)
