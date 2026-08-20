package com.refinvest.core.strategy.port.inbound.strategy.preview

fun interface PreviewStrategyUseCase {
    fun execute(command: PreviewStrategyCommand): PreviewStrategyResult?
}
