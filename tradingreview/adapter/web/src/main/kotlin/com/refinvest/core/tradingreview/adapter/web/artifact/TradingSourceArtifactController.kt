package com.refinvest.core.tradingreview.adapter.web.artifact

import com.refinvest.core.tradingreview.adapter.web.deletion.TradingDeletionResponse
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.inbound.importsession.deleteartifact.DeleteTradingSourceArtifactCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.deleteartifact.DeleteTradingSourceArtifactUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/trading-source-artifacts")
class TradingSourceArtifactController(
    private val deleteArtifact: DeleteTradingSourceArtifactUseCase,
) {
    @DeleteMapping("/{artifactId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable artifactId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(deleteArtifact.execute(DeleteTradingSourceArtifactCommand(TradingSourceArtifactId(artifactId), key)))
}
