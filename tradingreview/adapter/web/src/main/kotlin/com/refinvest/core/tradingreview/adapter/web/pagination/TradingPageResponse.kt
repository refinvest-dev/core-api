package com.refinvest.core.tradingreview.adapter.web.pagination



data class TradingPageResponse<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)
