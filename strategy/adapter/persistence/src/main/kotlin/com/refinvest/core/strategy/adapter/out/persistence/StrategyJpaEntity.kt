package com.refinvest.core.strategy.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.CascadeType
import java.time.Instant

@Entity
@Table(name = "strategies")
class StrategyJpaEntity(
    @Id
    var id: Long,
    @Column(name = "member_id", nullable = false)
    var memberId: Long,
    @Column(nullable = false, length = 200)
    var name: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
    @OneToMany(mappedBy = "strategy", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var versions: MutableList<StrategyVersionJpaEntity> = mutableListOf(),
) {
    fun replaceVersions(newVersions: List<StrategyVersionJpaEntity>) {
        versions.clear()
        versions += newVersions
    }
}
