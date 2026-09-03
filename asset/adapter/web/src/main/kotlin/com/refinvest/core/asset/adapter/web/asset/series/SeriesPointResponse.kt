package com.refinvest.core.asset.adapter.web.asset.series

import java.math.BigDecimal
import java.time.LocalDate

data class SeriesPointResponse(val date: LocalDate, val value: BigDecimal)
