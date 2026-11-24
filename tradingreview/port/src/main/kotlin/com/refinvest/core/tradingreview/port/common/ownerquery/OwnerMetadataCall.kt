package com.refinvest.core.tradingreview.port.common.ownerquery

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class OwnerMetadataCall(
    val result: CompletionStage<OwnerMetadataResult>,
    val timeout: Duration,
    private val cancelRequest: () -> Unit,
) {
    fun cancel() = cancelRequest()

    companion object {
        fun completed(result: OwnerMetadataResult): OwnerMetadataCall =
            OwnerMetadataCall(CompletableFuture.completedFuture(result), Duration.ofMillis(1)) {}
    }
}
