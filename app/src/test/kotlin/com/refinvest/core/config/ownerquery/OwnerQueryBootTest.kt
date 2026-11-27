package com.refinvest.core.config.ownerquery

import com.refinvest.OwnerQueryProxyConfigurationTest
import com.refinvest.core.auth.adapter.web.authority.MemberAuthorityController
import com.refinvest.core.auth.adapter.web.authority.MemberAuthoritySecurityConfiguration
import com.refinvest.core.auth.adapter.web.config.SecurityConfiguration
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import com.refinvest.core.auth.port.inbound.delegation.IssueOwnerQueryDelegationUseCase
import com.refinvest.core.auth.port.outbound.member.CurrentMemberIdProvider
import com.refinvest.core.config.OwnerQueryProxyConfiguration
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.adapter.web.account.TradingAccountController
import com.refinvest.core.tradingreview.adapter.web.book.TradingBookController
import com.refinvest.core.tradingreview.adapter.web.error.TradingReviewExceptionHandler
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.core.env.MapPropertySource
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.GenericWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.time.Clock
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerQueryBootTest {
    private lateinit var snapshot: Path
    private val primary = mock(DataSource::class.java)

    @BeforeAll
    fun inputs(@TempDir directory: Path) {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val store = directory.resolve("synthetic-ca.p12")
        val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keystore", store.toString(),
            "-storetype", "PKCS12", "-storepass", "fixture-only", "-dname", "CN=synthetic", "-ext", "bc=ca:true",
            "-validity", "2", "-keyalg", "RSA", "-keysize", "2048", "-noprompt")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Synthetic CA generation timed out") }
        check(process.exitValue() == 0) { "Synthetic CA generation failed" }
        val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, "fixture-only".toCharArray()) } }
        fun pem(label: String, bytes: ByteArray) = "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes) + "\n-----END $label-----"
        val input = mapOf("issuer" to "https://synthetic.invalid/issuer", "signingKid" to "synthetic", "privateKey" to pem("PRIVATE KEY", key.private.encoded),
            "keys" to listOf(mapOf("issuer" to "https://synthetic.invalid/issuer", "kid" to "synthetic", "publicKey" to pem("PUBLIC KEY", key.public.encoded))),
            "goOrigin" to "https://127.0.0.1:65534", "goCredential" to "A".repeat(43), "goRootCAs" to pem("CERTIFICATE", keys.getCertificate("fixture").encoded),
            "authorityCredentials" to listOf("B".repeat(43)), "requestTimeout" to "PT5S", "connectTimeout" to "PT1S",
            "shutdownTimeout" to "PT1S", "queryTimeoutSeconds" to 2, "maxInFlight" to 4)
        snapshot = directory.resolve("snapshot.json")
        Files.write(snapshot, JsonMapper.builder().build().writeValueAsBytes(input))
    }

    private fun selected(): MutableMap<String, Any> = mutableMapOf(
        "trading-review.owner-query-runtime.enabled" to "true", "trading-review.owner-query-proxy.enabled" to "true",
        "trading-review.owner-query-runtime.snapshot-file" to snapshot.toString(),
        "trading-review.owner-query-runtime.preparation-timeout" to "PT10S", "trading-review.owner-query-runtime.drain-timeout" to "PT1S",
        "server.ssl.enabled" to "true", "refinvest.security.web-origin" to "https://web.invalid",
    )

    private fun context(properties: Map<String, Any>, adapters: Boolean = true, duplicate: Boolean = false,
                        observed: AtomicReference<OwnerQueryRuntime>? = null): GenericWebApplicationContext {
        val context = GenericWebApplicationContext()
        context.servletContext = MockServletContext()
        context.environment.propertySources.addFirst(MapPropertySource("selected", properties))
        context.registerBean("primary", DataSource::class.java, Supplier { primary })
        context.registerBean("member", CurrentMemberIdProvider::class.java, Supplier { CurrentMemberIdProvider { MemberId(1) } })
        context.registerBean("clock", Clock::class.java, Supplier { Clock.systemUTC() })
        if (duplicate) context.registerBean("duplicateIssuer", IssueOwnerQueryDelegationUseCase::class.java, Supplier { mock(IssueOwnerQueryDelegationUseCase::class.java) })
        if (observed != null) context.beanFactory.addBeanPostProcessor(object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, name: String): Any {
                if (bean is OwnerQueryRuntime) observed.set(bean)
                return bean
            }
        })
        for (name in listOf("account.create.CreateTradingAccountUseCase", "account.get.GetTradingAccountUseCase", "account.list.ListTradingAccountsUseCase",
            "account.delete.DeleteTradingAccountUseCase", "book.get.GetTradingBookUseCase", "book.delete.DeleteTradingBookUseCase")) {
            @Suppress("UNCHECKED_CAST")
            val type = Class.forName("com.refinvest.core.tradingreview.port.inbound.$name") as Class<Any>
            context.registerBean(name, type, Supplier { mock(type) })
        }
        val reader = AnnotatedBeanDefinitionReader(context)
        // Discover the conditional adapters before their runtime Bean definitions.
        if (adapters) reader.register(MemberAuthorityController::class.java, MemberAuthoritySecurityConfiguration::class.java)
        reader.register(OwnerQueryRuntimeConfiguration::class.java, OwnerQueryProxyConfiguration::class.java,
            OwnerQueryProxyConfigurationTest.ProxyMvcConfiguration::class.java, SecurityConfiguration::class.java,
            TradingAccountController::class.java, TradingBookController::class.java, TradingReviewExceptionHandler::class.java)
        return context
    }

    @Test
    fun `default Boot configuration stays disabled without loading secrets`() {
        context(mapOf("refinvest.security.web-origin" to "https://web.invalid")).use { ctx ->
            ctx.refresh()
            assertTrue(ctx.getBeansOfType(OwnerQueryRuntime::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(CheckMemberAuthorityUseCase::class.java).isEmpty())
            assertFalse(ctx.getBean(OwnerQueryBootSettings::class.java).enabled)
        }
        verifyNoInteractions(primary)
    }

    @Test
    fun `partial conflicting insecure and duplicate configurations fail before lifecycle starts`() {
        val variants = listOf(
            selected().apply { remove("trading-review.owner-query-runtime.snapshot-file") },
            selected().apply { this["trading-review.owner-query-runtime.enabled"] = "false" },
            selected().apply { this["trading-review.owner-query-runtime.enabled"] = "yes" },
            selected().apply { this["trading-review.owner-query-proxy.enabled"] = "false" },
            selected().apply { this["server.ssl.enabled"] = "false" },
            selected().apply { this["trading-review.owner-query-runtime.drain-timeout"] = "PT0S" },
            selected().apply { this["trading-review.owner-query-runtime.snapshot-file"] = "missing-synthetic.json" },
            selected().apply { this["trading-review.owner-query-proxy.request-timeout"] = "PT4S" },
        )
        for (input in variants) context(input).use { ctx -> assertFails { ctx.refresh() } }
        context(selected(), duplicate = true).use { ctx -> assertFails { ctx.refresh() } }
        verifyNoInteractions(primary)
    }

    @Test
    fun `selected runtime registers one authority chain regardless of discovery order and preserves browser CSRF`() {
        context(selected()).use { ctx ->
            ctx.refresh()
            val runtime = ctx.getBean(OwnerQueryRuntime::class.java)
            assertTrue(runtime.isRunning)
            assertEquals(1, ctx.getBeansOfType(MemberAuthorityController::class.java).size)
            assertEquals(1, ctx.getBeansOfType(IssueOwnerQueryDelegationUseCase::class.java).size)
            assertSame(runtime.reader, ctx.getBean(OwnerMetadataReader::class.java))
            assertEquals("OwnerQueryBootSettings(redacted)", ctx.getBean(OwnerQueryBootSettings::class.java).toString())
            val mvc = MockMvcBuilders.webAppContextSetup(ctx)
                .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(ctx.getBean("springSecurityFilterChain", jakarta.servlet.Filter::class.java)).build()
            mvc.perform(post(MemberAuthorityController.PATH).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized).andExpect(header().string("Cache-Control", "no-store"))
            mvc.perform(post(MemberAuthorityController.PATH).header("Authorization", "Bearer " + "B".repeat(43)).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest)
            mvc.perform(get("/trading-accounts/1")).andExpect(status().isUnauthorized)
            mvc.perform(post("/trading-accounts").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden)
        }
        verifyNoInteractions(primary)
    }

    @Test
    fun `missing authority adapter closes an already prepared runtime on refresh failure`() {
        val observed = AtomicReference<OwnerQueryRuntime>()
        context(selected(), adapters = false, observed = observed).use { ctx -> assertFails { ctx.refresh() } }
        assertNotNull(observed.get())
        assertEquals(true, observed.get().shutdownComplete)
        verifyNoInteractions(primary)
    }

    @Test
    fun `settings and prepared inputs remain fixed after refresh`() {
        val local = Files.createTempFile(snapshot.parent, "prepared-runtime-", ".json")
        Files.copy(snapshot, local, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        context(selected().apply { this["trading-review.owner-query-runtime.snapshot-file"] = local.toString() }).use { ctx ->
            ctx.refresh()
            Files.delete(local)
            ctx.environment.propertySources.replace("selected", MapPropertySource("selected", selected().apply {
                this["trading-review.owner-query-runtime.snapshot-file"] = "missing-after-refresh.json"
                this["trading-review.owner-query-runtime.enabled"] = "false"
            }))
            val runtime = ctx.getBean(OwnerQueryRuntime::class.java)
            assertTrue(runtime.issuer.execute().compactToken.isNotEmpty())
            assertTrue(runtime.authorityCaller.accepts("B".repeat(43)))
            assertTrue(ctx.getBean(OwnerQueryBootSettings::class.java).enabled)
        }
        verifyNoInteractions(primary)
    }
}
