package com.refinvest

import com.refinvest.core.auth.adapter.web.authority.MemberAuthorityController
import com.refinvest.core.auth.adapter.web.authority.MemberAuthoritySecurityConfiguration
import com.refinvest.core.auth.port.inbound.authority.AuthenticateAuthorityCallerUseCase
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.core.annotation.Order
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.util.function.Supplier

internal class MemberAuthorityHttpFixture(check: CheckMemberAuthorityUseCase, caller: AuthenticateAuthorityCallerUseCase) : AutoCloseable {
    private val context = GenericWebApplicationContext().apply {
        servletContext = MockServletContext()
        registerBean("authorityCheck", CheckMemberAuthorityUseCase::class.java, Supplier { check })
        registerBean("authorityCaller", AuthenticateAuthorityCallerUseCase::class.java, Supplier { caller })
        AnnotatedBeanDefinitionReader(this).register(AuthorityMvcConfig::class.java,
            MemberAuthoritySecurityConfiguration::class.java, MemberAuthorityController::class.java)
        refresh()
    }
    val mapper: ObjectMapper = context.getBean(ObjectMapper::class.java)
    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context)
        .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter::class.java)).build()

    override fun close() = context.close()

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    class AuthorityMvcConfig {
        @Bean
        fun mapper(): ObjectMapper = JsonMapper.builder().build()

        @Bean
        @Order(100)
        fun browserFallback(http: HttpSecurity): SecurityFilterChain = http
            .authorizeHttpRequests { it.anyRequest().permitAll() }.build()
    }
}
