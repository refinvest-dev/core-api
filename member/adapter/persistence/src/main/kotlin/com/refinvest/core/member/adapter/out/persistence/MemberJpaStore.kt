package com.refinvest.core.member.adapter.out.persistence

import org.springframework.data.repository.CrudRepository

interface MemberJpaStore : CrudRepository<MemberJpaEntity, Long>
