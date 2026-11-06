package com.refinvest.core.auth.domain

import com.refinvest.core.auth.domain.valueobject.SocialIdentityId
import com.refinvest.core.auth.domain.valueobject.SocialProvider
import com.refinvest.core.shared.kernel.member.MemberId
import kotlin.test.*

class SocialIdentityTest {
    @Test
    fun `identity is provider scoped and preserves the provider subject exactly`() {
        val google = SocialIdentity.create(SocialIdentityId(SocialProvider.GOOGLE, "Subject:01"), MemberId(1))
        val kakao = SocialIdentity.restore(SocialIdentityId(SocialProvider.KAKAO, "Subject:01"), MemberId(2))
        assertNotEquals(google.id, kakao.id)
        assertNotEquals(google.id.value, kakao.id.value)
        assertEquals("Subject:01", google.providerSubject)
        assertEquals(MemberId(2), kakao.memberId)
    }

    @Test
    fun `blank subjects cannot be created or restored`() {
        assertFailsWith<IllegalArgumentException> { SocialIdentityId(SocialProvider.GOOGLE, " ") }
    }
}
