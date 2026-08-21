package com.refinvest.core.auth.adapter.security.oauth

import com.refinvest.core.auth.domain.SocialProvider
import com.refinvest.core.auth.port.inbound.auth.login.SocialLoginCommand
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Component

@Component
class ProviderIdentityNormalizer {
    fun normalize(authentication: OAuth2AuthenticationToken): SocialLoginCommand {
        val provider = when (authentication.authorizedClientRegistrationId.lowercase()) {
            "kakao" -> SocialProvider.KAKAO
            "naver" -> SocialProvider.NAVER
            "google" -> SocialProvider.GOOGLE
            else -> throw IllegalArgumentException("Unsupported social provider")
        }
        val attributes = authentication.principal.attributes
        val subject = when (provider) {
            SocialProvider.KAKAO -> attributes["id"]
            SocialProvider.GOOGLE -> attributes["sub"]
            SocialProvider.NAVER -> (attributes["response"] as? Map<*, *>)?.get("id")
        }?.toString()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Provider subject is missing")

        return SocialLoginCommand(provider, subject)
    }
}
