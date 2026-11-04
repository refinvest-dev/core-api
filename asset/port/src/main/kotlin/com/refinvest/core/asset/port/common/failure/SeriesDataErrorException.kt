package com.refinvest.core.asset.port.common.failure

/** Stable failure meaning shared by data providers and callers; HTTP mapping belongs to Web. */
enum class SeriesDataErrorCode {
    DATASET_UNAVAILABLE,
    DATASET_CORRUPTION,
}

class SeriesDataErrorException(
    val errorCode: SeriesDataErrorCode,
    message: String,
) : RuntimeException(message)
