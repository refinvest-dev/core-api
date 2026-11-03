package com.refinvest.core.tradingreview.port.pagination

import com.refinvest.core.tradingreview.domain.*

data class TradingPage<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)
