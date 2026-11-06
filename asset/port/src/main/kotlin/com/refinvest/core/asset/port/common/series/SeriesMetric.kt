package com.refinvest.core.asset.port.common.series

/** Provider-neutral query option shared by the inbound series query and outbound data request. */
enum class SeriesMetric {
    PRICE,
    RETURN,
    NORMALIZED,
}
