package com.refinvest.core.config.ownerquery

import com.refinvest.core.auth.port.inbound.authority.AuthenticateAuthorityCallerUseCase
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import com.refinvest.core.auth.port.inbound.delegation.IssueOwnerQueryDelegationUseCase
import com.refinvest.core.auth.port.outbound.member.CurrentMemberIdProvider
import com.refinvest.core.auth.adapter.web.authority.MemberAuthorityController
import com.refinvest.core.tradingreview.port.inbound.ownerquery.ProxyOwnerMetadataUseCase
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.web.SecurityFilterChain
import java.time.Clock
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class OwnerQueryRuntimeConfiguration {
    @Bean
    fun ownerQueryBootSettings(environment: Environment): OwnerQueryBootSettings = OwnerQueryBootSettings.read(environment)

    @Bean
    fun ownerQueryModeConsistency(settings: OwnerQueryBootSettings, factory: ListableBeanFactory): SmartInitializingSingleton = SmartInitializingSingleton {
        val count = factory.getBeanNamesForType(OwnerQueryRuntime::class.java).size
        if (count != if (settings.enabled) 1 else 0) throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.CAPABILITIES)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "trading-review.owner-query-runtime", name = ["enabled"], havingValue = "true")
    class Enabled {
        @Bean(destroyMethod = "close")
        fun ownerQueryRuntime(settings: OwnerQueryBootSettings, primary: DataSource, member: CurrentMemberIdProvider, clock: Clock): OwnerQueryRuntime {
            val prepared = OwnerQueryPreparation.prepare(requireNotNull(settings.snapshotFile), primary, member, clock, requireNotNull(settings.preparationTimeout))
            return try {
                OwnerQueryRuntime(prepared, requireNotNull(settings.drainTimeout))
            } catch (_: Exception) {
                prepared.close()
                throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.CAPABILITIES)
            }
        }

        @Bean fun ownerQueryIssuer(runtime: OwnerQueryRuntime): IssueOwnerQueryDelegationUseCase = runtime.issuer
        @Bean fun ownerQueryAuthority(runtime: OwnerQueryRuntime): CheckMemberAuthorityUseCase = runtime.authority
        @Bean fun ownerQueryAuthorityCaller(runtime: OwnerQueryRuntime): AuthenticateAuthorityCallerUseCase = runtime.authorityCaller
        @Bean fun ownerQueryReader(runtime: OwnerQueryRuntime): OwnerMetadataReader = runtime.reader

        @Bean
        fun ownerQueryTopology(factory: ListableBeanFactory, runtime: OwnerQueryRuntime): SmartInitializingSingleton = SmartInitializingSingleton {
            try {
                for (type in listOf(IssueOwnerQueryDelegationUseCase::class.java, CheckMemberAuthorityUseCase::class.java,
                    AuthenticateAuthorityCallerUseCase::class.java, OwnerMetadataReader::class.java, ProxyOwnerMetadataUseCase::class.java,
                    MemberAuthorityController::class.java)) {
                    require(factory.getBeanNamesForType(type).size == 1)
                }
                require(factory.getBeanNamesForType(SecurityFilterChain::class.java).count { it == "authoritySecurityChain" } == 1)
                require(factory.getBean(IssueOwnerQueryDelegationUseCase::class.java) === runtime.issuer)
                require(factory.getBean(OwnerMetadataReader::class.java) === runtime.reader)
            } catch (_: Exception) {
                throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.CAPABILITIES)
            }
        }
    }
}
