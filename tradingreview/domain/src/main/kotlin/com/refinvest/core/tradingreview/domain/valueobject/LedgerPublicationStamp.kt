package com.refinvest.core.tradingreview.domain.valueobject

import java.time.Instant

data class LedgerPublicationStamp(val revisionNumber: Long, val publishedAt: Instant) {
    init { require(revisionNumber >= 1) }
}
