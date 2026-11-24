package com.refinvest

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.refinvest.core.auth.adapter.security.delegation.*
import com.refinvest.core.auth.adapter.security.member.SecurityContextMemberIdProvider
import com.refinvest.core.auth.adapter.web.config.SecurityConfiguration
import com.refinvest.core.auth.adapter.web.cookie.AuthCookieWriter
import com.refinvest.core.auth.adapter.web.error.JsonAccessDeniedHandler
import com.refinvest.core.auth.adapter.web.error.JsonAuthenticationEntryPoint
import com.refinvest.core.auth.adapter.web.oauth.*
import com.refinvest.core.auth.application.delegation.IssueOwnerQueryDelegationService
import com.refinvest.core.auth.port.inbound.delegation.IssueOwnerQueryDelegationUseCase
import com.refinvest.core.config.OwnerQueryProxyConfiguration
import com.refinvest.core.tradingreview.adapter.web.account.TradingAccountController
import com.refinvest.core.tradingreview.adapter.web.book.TradingBookController
import com.refinvest.core.tradingreview.adapter.web.error.TradingReviewExceptionHandler
import com.refinvest.core.tradingreview.adapter.web.ownerquery.OwnerQueryProxyHeadersFilter
import com.refinvest.core.tradingreview.port.common.ownerquery.*
import com.refinvest.core.tradingreview.port.inbound.ownerquery.ProxyOwnerMetadataUseCase
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.*
import org.springframework.core.env.MapPropertySource
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class OwnerQueryProxyConfigurationTest {
    @Test fun `default configuration is disabled and enabled without capabilities fails startup`() {
        AnnotationConfigApplicationContext().use { context ->
            context.register(OwnerQueryProxyConfiguration::class.java)
            context.refresh()
            assertTrue(context.getBeansOfType(ProxyOwnerMetadataUseCase::class.java).isEmpty())
        }
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("test",mapOf(
                "trading-review.owner-query-proxy.enabled" to "true","trading-review.owner-query-proxy.request-timeout" to "2s")))
            context.register(OwnerQueryProxyConfiguration::class.java)
            assertFails { context.refresh() }
        }
    }

    @Test fun `real browser authentication issues RS256 from principal and keeps CSRF enforcement`() {
        val pair=KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
        val key=OwnerQuerySigningKey.prepare("test-key",pair.public as RSAPublicKey,pair.private as RSAPrivateKey)
        val issuer=IssueOwnerQueryDelegationService(SecurityContextMemberIdProvider(),
            NimbusOwnerQueryDelegationSigner("https://issuer.invalid/delegation"){key},Clock.systemUTC())
        val observed=AtomicReference<String>()
        val reader=OwnerMetadataReader { request,delegation,_ ->
            observed.set(delegation.compactToken)
            OwnerMetadataCall.completed(OwnerMetadataResult.Account(request.resourceId,"BINANCE","owner","ACTIVE",Instant.now()))
        }
        GenericWebApplicationContext().use { context ->
            context.servletContext=MockServletContext()
            context.environment.propertySources.addFirst(MapPropertySource("test",mapOf(
                "refinvest.security.web-origin" to "https://web.invalid", "trading-review.owner-query-proxy.enabled" to "true",
                "trading-review.owner-query-proxy.request-timeout" to "2s")))
            context.registerBean("delegation",IssueOwnerQueryDelegationUseCase::class.java,Supplier{issuer})
            context.registerBean("reader",OwnerMetadataReader::class.java,Supplier{reader})
            legacyPorts.forEach { name ->
                val type=Class.forName("com.refinvest.core.tradingreview.port.inbound.$name")
                @Suppress("UNCHECKED_CAST")
                context.registerBean(name,type as Class<Any>,Supplier{mock(type)})
            }
            AnnotatedBeanDefinitionReader(context).register(ProxyMvcConfiguration::class.java,OwnerQueryProxyConfiguration::class.java,
                SecurityConfiguration::class.java,TradingAccountController::class.java,TradingBookController::class.java,
                TradingReviewExceptionHandler::class.java,OwnerQueryProxyHeadersFilter::class.java)
            context.refresh()
            val mvc=MockMvcBuilders.webAppContextSetup(context).addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(
                context.getBean(OwnerQueryProxyHeadersFilter::class.java),context.getBean("springSecurityFilterChain",jakarta.servlet.Filter::class.java)).build()
            mvc.perform(get("/trading-accounts/1")).andExpect(status().isUnauthorized).andExpect(header().string("Cache-Control","no-store"))
            val signed=JWSObject(JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),Payload(mapOf(
                "sub" to "9007199254740993","role" to "MEMBER","iat" to Instant.now().epochSecond,"exp" to Instant.now().plusSeconds(60).epochSecond)))
            signed.sign(MACSigner(hmac))
            val cookie=Cookie(AuthCookieWriter.ACCESS_COOKIE_NAME,signed.serialize())
            val pending=mvc.perform(get("/trading-accounts/9223372036854775806").cookie(cookie)
                .header("Authorization","Bearer browser-forged-service").header("X-RefInvest-User-Delegation","forged")
                .header("X-Owner-ID","2")).andExpect(request().asyncStarted()).andReturn()
            mvc.perform(asyncDispatch(pending)).andExpect(status().isOk).andExpect(jsonPath("$.id").value("9223372036854775806"))
            val delegated=JWSObject.parse(observed.get())
            assertTrue(delegated.verify(RSASSAVerifier(pair.public as RSAPublicKey)))
            assertEquals("9007199254740993",delegated.payload.toJSONObject()["sub"])
            assertEquals(mapOf("sub" to "core-api"),delegated.payload.toJSONObject()["act"])
            mvc.perform(post("/trading-accounts").cookie(cookie).contentType("application/json").content("{}")).andExpect(status().isForbidden)
        }
    }

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    class ProxyMvcConfiguration {
        @Bean fun mapper():ObjectMapper=JsonMapper.builder().build()
        @Bean fun success():SocialLoginSuccessHandler=mock(SocialLoginSuccessHandler::class.java)
        @Bean fun failure():SocialLoginFailureHandler=mock(SocialLoginFailureHandler::class.java)
        @Bean fun returnTo():ReturnToAuthorizationRequestResolver=mock(ReturnToAuthorizationRequestResolver::class.java)
        @Bean fun validation(mapper:ObjectMapper)=ReturnToValidationFilter(mapper)
        @Bean fun entry(mapper:ObjectMapper)=JsonAuthenticationEntryPoint(mapper)
        @Bean fun denied(mapper:ObjectMapper)=JsonAccessDeniedHandler(mapper)
        @Bean fun accessTokenJwtDecoder():JwtDecoder=NimbusJwtDecoder.withSecretKey(SecretKeySpec(hmac,"HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256).build()
    }

    companion object {
        private val hmac=ByteArray(64){65}
        private val legacyPorts=listOf("account.create.CreateTradingAccountUseCase","account.get.GetTradingAccountUseCase",
            "account.list.ListTradingAccountsUseCase","account.delete.DeleteTradingAccountUseCase",
            "book.get.GetTradingBookUseCase","book.delete.DeleteTradingBookUseCase")
    }
}
