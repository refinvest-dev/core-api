package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.port.ArtifactReadGrantRedeemer
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class TradingArtifactGrantController(private val grants: ArtifactReadGrantRedeemer) {
    @GetMapping("/internal/trading-review/artifact-grants/{token}")
    fun read(@PathVariable token: String): ResponseEntity<InputStreamResource> {
        val grant = grants.redeem(token)
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, grant.mediaType)
            .contentLength(grant.contentLength).body(InputStreamResource(grant.input))
    }
}
