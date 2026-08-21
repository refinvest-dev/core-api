package com.refinvest.core.member.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "members")
class MemberJpaEntity(
    @Id
    var id: Long,
    @Column(nullable = false, length = 20)
    var role: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
)
