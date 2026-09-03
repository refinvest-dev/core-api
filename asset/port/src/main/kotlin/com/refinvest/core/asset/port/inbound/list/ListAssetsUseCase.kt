package com.refinvest.core.asset.port.inbound.list

fun interface ListAssetsUseCase {
    fun execute(): ListAssetsResult
}
