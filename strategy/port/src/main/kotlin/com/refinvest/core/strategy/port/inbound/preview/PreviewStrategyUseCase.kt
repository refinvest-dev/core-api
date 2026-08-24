package com.refinvest.core.strategy.port.inbound.preview

fun interface PreviewStrategyUseCase {
    fun execute(command: PreviewStrategyCommand): PreviewStrategyResult?
}
