package com.refinvest.core.auth.adapter.web.cookie

import com.refinvest.core.auth.adapter.web.config.AuthWebProperties
import com.refinvest.core.auth.port.inbound.session.RefreshSessionResult
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthCookieWriterTest {
    private val now = Instant.parse("2026-11-03T00:00:00Z")
    private val writer = AuthCookieWriter(AuthWebProperties("https://web.example", true, "Strict"), Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `issued cookies preserve security attributes paths and remaining lifetime`() {
        val response = MockHttpServletResponse()
        writer.write(response, RefreshSessionResult("access", now.plusSeconds(900), "refresh", now.plusSeconds(3600)))
        val headers = response.getHeaders("Set-Cookie")
        assertEquals(2, headers.size)
        assertTrue(headers.all { it.contains("HttpOnly") && it.contains("Secure") && it.contains("SameSite=Strict") })
        assertTrue(headers.single { it.startsWith("REFINVEST_ACCESS_TOKEN=") }.let { it.contains("Path=/;") && it.contains("Max-Age=900") })
        assertTrue(headers.single { it.startsWith("REFINVEST_REFRESH_TOKEN=") }.let { it.contains("Path=/auth;") && it.contains("Max-Age=3600") })
    }

    @Test
    fun `clear removes access refresh and servlet cookies with their original paths`() {
        val response = MockHttpServletResponse()
        writer.clear(response)
        val headers = response.getHeaders("Set-Cookie")
        assertEquals(3, headers.size)
        assertTrue(headers.all { it.contains("Max-Age=0") && it.contains("HttpOnly") && it.contains("Secure") && it.contains("SameSite=Strict") })
        assertTrue(headers.single { it.startsWith("REFINVEST_REFRESH_TOKEN=") }.contains("Path=/auth;"))
        assertTrue(headers.single { it.startsWith("JSESSIONID=") }.contains("Path=/;"))
    }
}
