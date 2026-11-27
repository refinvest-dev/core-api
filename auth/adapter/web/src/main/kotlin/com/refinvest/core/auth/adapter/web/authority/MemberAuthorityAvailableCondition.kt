package com.refinvest.core.auth.adapter.web.authority

import com.refinvest.core.auth.port.inbound.authority.AuthenticateAuthorityCallerUseCase
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase

/** Selected runtime definitions may be discovered after this adapter during component scanning. */
class MemberAuthorityAvailableCondition : AnyNestedCondition(ConfigurationPhase.REGISTER_BEAN) {
    @ConditionalOnBean(CheckMemberAuthorityUseCase::class, AuthenticateAuthorityCallerUseCase::class)
    class ExplicitCapabilities

    @ConditionalOnProperty(prefix = "trading-review.owner-query-runtime", name = ["enabled"], havingValue = "true")
    class RuntimeSelected
}
