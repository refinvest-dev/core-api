package com.refinvest.core.auth.adapter.security.oauth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.stereotype.Component
import java.net.URI

@Component
class ReturnToAuthorizationRequestResolver(
    private val clientRegistrationRepositoryProvider: ObjectProvider<ClientRegistrationRepository>,
) : OAuth2AuthorizationRequestResolver {
    override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? =
        delegate()?.resolve(request)?.also { authorizationRequest -> storeReturnTo(request, authorizationRequest) }

    override fun resolve(request: HttpServletRequest, clientRegistrationId: String): OAuth2AuthorizationRequest? =
        delegate()?.resolve(request, clientRegistrationId)?.also { authorizationRequest -> storeReturnTo(request, authorizationRequest) }

    private fun delegate(): DefaultOAuth2AuthorizationRequestResolver? =
        clientRegistrationRepositoryProvider.getIfAvailable()?.let { clientRegistrationRepository ->
            DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization")
        }

    private fun storeReturnTo(request: HttpServletRequest, authorizationRequest: OAuth2AuthorizationRequest) {
        val returnTo = request.getParameter(RETURN_TO_PARAMETER) ?: "/"
        require(isSafeRelativePath(returnTo)) { "returnTo must be a Web-relative path" }
        request.getSession(true).setAttribute(attributeName(requireNotNull(authorizationRequest.state)), returnTo)
    }

    companion object {
        const val RETURN_TO_PARAMETER = "returnTo"
        const val AUTHORIZATION_PATH_PREFIX = "/oauth2/authorization/"

        fun attributeName(state: String): String = "refinvest.auth.return-to.$state"

        fun isSafeRelativePath(value: String): Boolean = runCatching {
            val uri = URI(value)
            value.startsWith('/') &&
                !value.startsWith("//") &&
                !value.contains('\\') &&
                !uri.isAbsolute &&
                uri.rawAuthority == null
        }.getOrDefault(false)
    }
}
