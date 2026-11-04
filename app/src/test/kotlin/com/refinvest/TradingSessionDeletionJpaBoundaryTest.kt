package com.refinvest

import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingImportSessionReaderAdapter
import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingImportSessionStoreAdapter
import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingSourceArtifactReaderAdapter
import com.refinvest.core.tradingreview.adapter.persistence.TradingImportSessionJpaEntity
import com.refinvest.core.tradingreview.adapter.persistence.TradingSourceArtifactJpaEntity
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradingSessionDeletionJpaBoundaryTest {
    @Test
    fun `deletion general persistence keeps a shared scalar checkpoint snapshot and separate adapters`() {
        val root = com.refinvest.core.tradingreview.adapter.persistence.DeletionRequestJpaEntity::class.java
        assertTrue(root.isAnnotationPresent(Entity::class.java))
        assertEquals(1, root.declaredFields.count { it.isAnnotationPresent(Version::class.java) })
        assertEquals(listOf(com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore::class.java),
            com.refinvest.core.tradingreview.adapter.persistence.JpaDeletionRequestStoreAdapter::class.java.interfaces.toList())
        assertEquals(1, com.refinvest.core.tradingreview.adapter.persistence.JpaDeletionRequestReaderAdapter::class.java.interfaces.size)
        assertEquals(listOf(com.refinvest.core.tradingreview.adapter.persistence.DeletionRequestJpaStore::class.java),
            com.refinvest.core.tradingreview.adapter.persistence.DeletionRequestSnapshots::class.java.constructors.single().parameterTypes.toList())
    }

    @Test
    fun `session writes own artifact slots and adapters separate command and query capabilities`() {
        listOf(TradingImportSessionJpaEntity::class.java, TradingSourceArtifactJpaEntity::class.java).forEach { type ->
            assertTrue(type.isAnnotationPresent(Entity::class.java))
            assertEquals(1, type.declaredFields.count { it.isAnnotationPresent(Version::class.java) })
            assertTrue(type.declaredFields.none { field -> field.annotations.any {
                it.annotationClass.simpleName in setOf("OneToMany", "ManyToOne", "OneToOne", "ManyToMany")
            } })
        }
        assertFalse(TradingSourceArtifactJpaEntity::class.java.getDeclaredField("sessionId")
            .getAnnotation(Column::class.java).updatable)
        assertEquals(listOf(TradingImportSessionStore::class.java), JpaTradingImportSessionStoreAdapter::class.java.interfaces.toList())
        listOf(JpaTradingImportSessionReaderAdapter::class.java, JpaTradingSourceArtifactReaderAdapter::class.java).forEach {
            assertEquals(1, it.interfaces.size)
            assertTrue(it.interfaces.single().simpleName.endsWith("Reader"))
        }
    }
}
