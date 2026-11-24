package com.refinvest.core.tradingreview.adapter.reviewapi

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

internal class TlsFixture(identity: KeyStore, handler: (HttpExchange)->Unit) : AutoCloseable {
    private val executor=Executors.newVirtualThreadPerTaskExecutor()
    private val server=HttpsServer.create(InetSocketAddress("127.0.0.1",0),0)
    val certificate=identity.getCertificate("fixture") as X509Certificate
    val origin: URI

    init {
        val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(identity,"fixture-only".toCharArray())
        val tls=SSLContext.getInstance("TLS").apply { init(keys.keyManagers,null,null) }
        server.httpsConfigurator=HttpsConfigurator(tls)
        server.executor=executor
        server.createContext("/") { exchange ->
            try { handler(exchange) } catch (_: java.io.IOException) { } finally { exchange.close() }
        }
        server.start()
        origin=URI("https://127.0.0.1:${server.address.port}")
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        executor.close()
    }

    companion object {
        fun identity(directory: Path): KeyStore {
            val file=directory.resolve("fixture.p12")
            val executable=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows"))"keytool.exe" else "keytool")
            val process=ProcessBuilder(executable.toString(),"-genkeypair","-alias","fixture","-keystore",file.toString(),
                "-storetype","PKCS12","-storepass","fixture-only","-keypass","fixture-only","-dname","CN=localhost",
                "-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","2","-keyalg","RSA","-keysize","2048","-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Test TLS identity generation timed out")
            }
            check(process.exitValue()==0){"Test TLS identity generation failed"}
            return KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it,"fixture-only".toCharArray()) } }
        }
    }
}
