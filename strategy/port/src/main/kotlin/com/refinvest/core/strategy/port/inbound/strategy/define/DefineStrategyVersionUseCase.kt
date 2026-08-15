package com.refinvest.core.strategy.port.inbound.strategy.define

fun interface DefineStrategyVersionUseCase {
    fun execute(command: DefineStrategyVersionCommand): DefineStrategyVersionResult?
}
