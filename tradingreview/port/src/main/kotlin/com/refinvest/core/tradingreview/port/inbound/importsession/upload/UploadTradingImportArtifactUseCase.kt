package com.refinvest.core.tradingreview.port.inbound.importsession.upload

fun interface UploadTradingImportArtifactUseCase {
    fun execute(command: UploadTradingImportArtifactCommand): UploadArtifactResult
}
