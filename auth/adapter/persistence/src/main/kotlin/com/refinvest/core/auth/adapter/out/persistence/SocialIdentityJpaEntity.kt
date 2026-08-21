package com.refinvest.core.auth.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "social_identities",
    uniqueConstraints = [UniqueConstraint(name = "uk_social_identity_provider_subject", columnNames = ["provider", "provider_subject"])],
)
class SocialIdentityJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(nullable = false, length = 20)
    var provider: String,
    @Column(name = "provider_subject", nullable = false, length = 255)
    var providerSubject: String,
    @Column(name = "member_id", nullable = false)
    var memberId: Long,
)
