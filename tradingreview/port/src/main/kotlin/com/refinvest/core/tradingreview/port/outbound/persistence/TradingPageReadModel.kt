package com.refinvest.core.tradingreview.port.outbound.persistence

/** A persistence page; application maps its items into inbound results. */
data class TradingPageReadModel<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)
