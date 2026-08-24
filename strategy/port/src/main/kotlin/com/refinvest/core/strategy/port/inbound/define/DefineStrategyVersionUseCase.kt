package com.refinvest.core.strategy.port.inbound.define

fun interface DefineStrategyVersionUseCase {
    fun execute(command: DefineStrategyVersionCommand): DefineStrategyVersionResult?
}
