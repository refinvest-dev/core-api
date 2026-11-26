package com.refinvestfixture.ownerquery

import com.refinvest.RefinvestApplication
import com.refinvest.core.auth.adapter.out.persistence.JdbcMemberAuthorityReader
import com.refinvest.core.auth.adapter.security.authority.NimbusAuthorityDelegationVerifier
import com.refinvest.core.auth.adapter.security.authority.OpaqueAuthorityCredentialVerifier
import com.refinvest.core.auth.adapter.security.delegation.NimbusOwnerQueryDelegationSigner
import com.refinvest.core.auth.adapter.security.delegation.OwnerQuerySigningKey
import com.refinvest.core.auth.adapter.security.member.SecurityContextMemberIdProvider
import com.refinvest.core.auth.application.authority.AuthenticateAuthorityCallerService
import com.refinvest.core.auth.application.authority.CheckMemberAuthorityService
import com.refinvest.core.auth.application.delegation.IssueOwnerQueryDelegationService
import com.refinvest.core.auth.port.inbound.authority.AuthenticateAuthorityCallerUseCase
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import com.refinvest.core.auth.port.inbound.delegation.IssueOwnerQueryDelegationUseCase
import com.refinvest.core.tradingreview.adapter.reviewapi.OwnerMetadataHttpClient
import com.refinvest.core.tradingreview.adapter.reviewapi.OwnerMetadataHttpOptions
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.function.Supplier
import javax.sql.DataSource
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
/** Test classpath only. Starts the real Core application with explicit synthetic capabilities. */
fun main(args: Array<String>) {
    val path = requireNotNull(System.getenv("REFINVEST_OWNER_QUERY_FIXTURE_FILE")) { "Fixture file required" }
    val application = SpringApplication(RefinvestApplication::class.java)
    application.addInitializers(OwnerQueryServiceFixture(Path.of(path)))
    application.run(*args)
}

class OwnerQueryServiceFixture(private val path: Path) : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        require(context is GenericApplicationContext) { "Fixture servlet context required" }
        val environment = context.environment
        val target = URI(requireNotNull(environment.getProperty("spring.datasource.url")).removePrefix("jdbc:"))
        require(target.scheme == "postgresql" && target.host in setOf("127.0.0.1", "localhost", "::1") &&
            target.path == "/refinvest_integration" && target.rawQuery?.matches(Regex("currentSchema=tnorm_[0-9a-f]{12}")) == true &&
            environment.getProperty("server.address") == "127.0.0.1") { "Isolated loopback integration fixture required" }
        require(Files.isRegularFile(path) && Files.size(path) in 1..65536) { "Invalid fixture file" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(65537) }
        require(bytes.size <= 65536) { "Invalid fixture file" }
        val source = JsonMapper.builder().build().readTree(bytes)
        fun text(name: String): String = source[name]?.takeIf { it.isString }?.asString()
            ?.takeIf { it.isNotBlank() } ?: error("Missing fixture input")
        fun der(name: String, label: String): ByteArray = Base64.getMimeDecoder().decode(
            text(name).removePrefix("-----BEGIN $label-----").trim().removeSuffix("-----END $label-----").trim(),
        )

        val factory = KeyFactory.getInstance("RSA")
        val publicKey = factory.generatePublic(X509EncodedKeySpec(der("publicKey", "PUBLIC KEY"))) as RSAPublicKey
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(der("privateKey", "PRIVATE KEY"))) as RSAPrivateKey
        val issuer = text("issuer")
        val kid = text("kid")
        val signing = OwnerQuerySigningKey.prepare(kid, publicKey, privateKey)
        val clock = Clock.systemUTC()
        val credential = text("serviceCredential")
        val authorityCredential = text("authorityCredential")
        require(credential != authorityCredential) { "Separated fixture credentials required" }
        val origin = URI(text("goOrigin"))
        require(origin.scheme == "https" && origin.host == "127.0.0.1") { "Loopback TLS fixture required" }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(text("goRootCA").byteInputStream()) as X509Certificate

        context.registerBean("fixtureDelegationIssuer", IssueOwnerQueryDelegationUseCase::class.java, Supplier {
            IssueOwnerQueryDelegationService(SecurityContextMemberIdProvider(),
                NimbusOwnerQueryDelegationSigner(issuer) { signing }, clock)
        })
        context.registerBean("fixtureAuthorityCheck", CheckMemberAuthorityUseCase::class.java, Supplier {
            CheckMemberAuthorityService(NimbusAuthorityDelegationVerifier(mapOf((issuer to kid) to publicKey)),
                JdbcMemberAuthorityReader(context.getBean(DataSource::class.java), 2), clock)
        })
        context.registerBean("fixtureAuthorityCaller", AuthenticateAuthorityCallerUseCase::class.java, Supplier {
            AuthenticateAuthorityCallerService(OpaqueAuthorityCredentialVerifier(listOf(authorityCredential)))
        })
        context.registerBean("fixtureOwnerReader", OwnerMetadataHttpClient::class.java, Supplier {
            OwnerMetadataHttpClient(OwnerMetadataHttpOptions(origin, { credential }, Duration.ofSeconds(2),
                Duration.ofSeconds(3), 8, listOf(certificate), setOf(authorityCredential)))
        }, BeanDefinitionCustomizer { it.setDestroyMethodName("close") })
        context.registerBean("fixtureAuthorityObservation", OwnerAuthorityObservationFilter::class.java, Supplier {
            OwnerAuthorityObservationFilter(path.parent.resolve("authority-observations.jsonl"))
        })
    }
}

/** Bounded fixture evidence only: records validated trace metadata, never identity or credentials. */
class OwnerAuthorityObservationFilter(private val destination: Path) : OncePerRequestFilter() {
    private val count = AtomicInteger()
    private val lock = Any()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.requestURI == "/internal/trading-review/v1/delegations/check" && count.getAndIncrement() < 256) {
            val parent = request.getHeader("traceparent") ?: ""
            if (Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}").matches(parent)) {
                val record = """{"parent":"$parent","baggagePresent":${request.getHeader("baggage") != null}}"""
                synchronized(lock) {
                    Files.writeString(destination, record + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                }
            }
        }
        chain.doFilter(request, response)
    }
}
