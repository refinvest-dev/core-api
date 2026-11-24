package com.refinvest.core.tradingreview.adapter.web.ownerquery

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix="trading-review.owner-query-proxy", name=["enabled"], havingValue="true")
class OwnerQueryProxyHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (OwnerMetadataWebProxy.isMetadataRead(request)) response.setHeader("Cache-Control","no-store")
        chain.doFilter(request,response)
    }
}
