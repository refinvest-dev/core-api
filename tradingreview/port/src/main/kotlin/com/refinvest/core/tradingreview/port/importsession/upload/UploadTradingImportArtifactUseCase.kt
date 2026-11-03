package com.refinvest.core.tradingreview.port.importsession.upload

fun interface UploadTradingImportArtifactUseCase {
    fun execute(command: UploadTradingImportArtifactCommand): UploadArtifactResult
}
