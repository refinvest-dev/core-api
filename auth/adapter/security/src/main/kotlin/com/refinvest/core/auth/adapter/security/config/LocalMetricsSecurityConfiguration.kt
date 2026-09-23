package com.refinvest.core.auth.adapter.security.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import java.net.InetAddress

@Configuration
@Profile("dev-observability")
class LocalMetricsSecurityConfiguration {
    @Bean
    fun localMetricsUserDetailsService(
        @Value("\${REFINVEST_METRICS_SCRAPE_PASSWORD}") password: String,
    ): UserDetailsService {
        require(password.length >= 32) { "REFINVEST_METRICS_SCRAPE_PASSWORD must be at least 32 characters" }
        val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
        return InMemoryUserDetailsManager(
            User.withUsername("prometheus")
                .password(encoder.encode(password))
                .roles("METRICS")
                .build(),
        )
    }

    @Bean
    @Order(1)
    fun localMetricsSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.securityMatcher("/actuator/prometheus")
        http.authorizeHttpRequests { requests ->
            requests.anyRequest().access { authentication, context ->
                val request = context.request
                val authorized = request.method == HttpMethod.GET.name() &&
                    InetAddress.getByName(request.remoteAddr).isLoopbackAddress &&
                    authentication.get().authorities.any { it.authority == "ROLE_METRICS" }
                AuthorizationDecision(authorized)
            }
        }
        http.httpBasic { }
        return http.build()
    }
}
