package com.refinvest.core.tradingreview.domain.valueobject

import java.util.Collections

/** Required scope definition only. It never infers progress or successful absence from the request state. */
class DeletionPurgeChecklist private constructor(
    components: Set<DeletionPurgeComponent>,
) {
    val requiredComponents: Set<DeletionPurgeComponent> = Collections.unmodifiableSet(components.toSet())
    companion object {
        internal fun forScope(scope: DeletionScope): DeletionPurgeChecklist {
            val raw = setOf(DeletionPurgeComponent.RAW_OBJECTS, DeletionPurgeComponent.RAW_KEYS,
                DeletionPurgeComponent.TEMPORARY_COPIES, DeletionPurgeComponent.COMPUTE_RUNTIME)
            val subtree = scope.targetType != DeletionTargetType.TRADING_SOURCE_ARTIFACT
            return DeletionPurgeChecklist(raw + if (subtree) setOf(DeletionPurgeComponent.PRIMARY_DATA,
                DeletionPurgeComponent.DERIVED_RESULTS) else emptySet())
        }
    }
}
