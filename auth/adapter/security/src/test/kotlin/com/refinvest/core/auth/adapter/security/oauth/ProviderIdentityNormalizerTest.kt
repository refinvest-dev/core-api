package com.refinvest.core.auth.adapter.security.oauth

import com.refinvest.core.auth.domain.SocialProvider
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import kotlin.test.assertEquals

class ProviderIdentityNormalizerTest {
    private val normalizer = ProviderIdentityNormalizer()

    @Test
    fun `normalizes Kakao stable identifier`() {
        assertEquals(
            SocialProvider.KAKAO to "123456789",
            normalizer.normalize(authentication("kakao", mapOf("id" to 123456789L), "id")).let { it.provider to it.providerSubject },
        )
    }

    @Test
    fun `normalizes Google subject`() {
        assertEquals(
            SocialProvider.GOOGLE to "google-subject",
            normalizer.normalize(authentication("google", mapOf("sub" to "google-subject"), "sub")).let { it.provider to it.providerSubject },
        )
    }

    @Test
    fun `normalizes Naver nested identifier`() {
        assertEquals(
            SocialProvider.NAVER to "naver-subject",
            normalizer.normalize(
                authentication("naver", mapOf("response" to mapOf("id" to "naver-subject")), "response"),
            ).let { it.provider to it.providerSubject },
        )
    }

    private fun authentication(
        registrationId: String,
        attributes: Map<String, Any>,
        nameAttributeKey: String,
    ) = OAuth2AuthenticationToken(
        DefaultOAuth2User(emptyList(), attributes, nameAttributeKey),
        emptyList(),
        registrationId,
    )
}
