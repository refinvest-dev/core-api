package com.refinvest.core.tradingreview.domain.valueobject

data class LedgerSectionDigest(val sectionType: String, val itemCount: Long,
                               val hashSpecificationVersion: String, val sectionHash: String) {
    init {
        require(sectionType.isNotBlank() && itemCount >= 0 && hashSpecificationVersion.isNotBlank())
        require(Regex("[0-9a-f]{64}").matches(sectionHash))
    }
    fun toWireValue(): Map<String, Any> = mapOf("sectionType" to sectionType, "itemCount" to itemCount,
        "hashSpecificationVersion" to hashSpecificationVersion, "sectionHash" to sectionHash)
}
