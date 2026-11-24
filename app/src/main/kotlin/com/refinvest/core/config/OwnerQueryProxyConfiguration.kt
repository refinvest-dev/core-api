package com.refinvest.core.config

import com.refinvest.core.auth.port.inbound.delegation.IssueOwnerQueryDelegationUseCase
import com.refinvest.core.auth.port.outbound.delegation.DelegationSigningException
import com.refinvest.core.tradingreview.application.ownerquery.ProxyOwnerMetadataService
import com.refinvest.core.tradingreview.port.inbound.ownerquery.ProxyOwnerMetadataUseCase
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerQueryDelegation
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerQueryDelegationIssuer
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerQueryDelegationUnavailable
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OwnerQueryProxySettings::class)
class OwnerQueryProxyConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "trading-review.owner-query-proxy", name = ["enabled"], havingValue = "true")
    fun ownerMetadataProxy(
        settings: OwnerQueryProxySettings,
        issuer: IssueOwnerQueryDelegationUseCase,
        reader: OwnerMetadataReader,
    ): ProxyOwnerMetadataUseCase {
        val budget = requireNotNull(settings.requestTimeout) { "Owner query request budget is required" }
        val delegationIssuer = OwnerQueryDelegationIssuer {
            try {
                val issued = issuer.execute()
                OwnerQueryDelegation(issued.compactToken, issued.expiresAt)
            } catch (_: DelegationSigningException) {
                throw OwnerQueryDelegationUnavailable()
            }
        }

        return ProxyOwnerMetadataService(delegationIssuer, reader, budget, Clock.systemUTC())
    }
}

@ConfigurationProperties("trading-review.owner-query-proxy")
data class OwnerQueryProxySettings(val enabled: Boolean = false, val requestTimeout: Duration? = null)
