package com.refinvest.core.asset.port.outbound.compute

enum class SeriesDataErrorCode {
    DATASET_UNAVAILABLE,
    DATASET_CORRUPTION,
}

class SeriesDataErrorException(
    val errorCode: SeriesDataErrorCode,
    message: String,
) : RuntimeException(message)
