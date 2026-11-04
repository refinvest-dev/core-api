package com.refinvest

import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingAccountReaderAdapter
import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingAccountStoreAdapter
import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingBookReaderAdapter
import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingBookStoreAdapter
import com.refinvest.core.tradingreview.adapter.persistence.TradingAccountJpaEntity
import com.refinvest.core.tradingreview.adapter.persistence.TradingAccountJpaStore
import com.refinvest.core.tradingreview.adapter.persistence.TradingBookJpaEntity
import com.refinvest.core.tradingreview.adapter.persistence.TradingBookJpaStore
import jakarta.persistence.Entity
import jakarta.persistence.Version
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.data.jpa.repository.JpaRepository

class TradingAccountBookJpaBoundaryTest {
    @Test
    fun `general aggregate JPA stores keep readers separate and preserve versioned scalar boundaries`() {
        listOf(TradingAccountJpaEntity::class.java, TradingBookJpaEntity::class.java).forEach { type ->
            assertTrue(type.isAnnotationPresent(Entity::class.java))
            assertEquals(1, type.declaredFields.count { it.isAnnotationPresent(Version::class.java) })
            assertTrue(type.declaredFields.none { field -> field.annotations.any {
                it.annotationClass.simpleName in setOf("OneToMany", "ManyToOne", "OneToOne", "ManyToMany")
            } }, "Other aggregate roots remain scalar IDs: ${type.name}")
        }
        listOf(TradingAccountJpaStore::class.java, TradingBookJpaStore::class.java).forEach {
            assertTrue(JpaRepository::class.java.isAssignableFrom(it))
        }
        listOf(JpaTradingAccountStoreAdapter::class.java, JpaTradingBookStoreAdapter::class.java,
            JpaTradingAccountReaderAdapter::class.java, JpaTradingBookReaderAdapter::class.java).forEach {
            assertEquals(1, it.interfaces.size, "One aggregate Store or independent Reader per adapter: ${it.name}")
        }
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        val sources = root.resolve("tradingreview/adapter/persistence/src/main/kotlin")
        Files.walk(sources).use { files ->
            files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val source = Files.readString(file)
                assertTrue(!Regex("class JdbcTrading(Account|Book)PersistenceAdapter").containsMatchIn(source), file.toString())
            }
        }
    }
}
