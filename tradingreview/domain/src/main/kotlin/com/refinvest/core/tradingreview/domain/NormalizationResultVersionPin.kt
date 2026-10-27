package com.refinvest.core.tradingreview.domain

/** Result wire provenance is independent of the ADR-065 input identity. */
data class NormalizationResultVersionPin(
    val schemaVersion: String,
    val hashSpecificationVersion: String,
) {
    init {
        require(isSupported(schemaVersion, hashSpecificationVersion)) {
            "Unsupported normalization result version pair"
        }
    }

    companion object {
        private const val SCHEMA_V1 = "TRADING_NORMALIZATION_RESULT_V1"
        private const val HASH_V1 = "TRADING_NORMALIZATION_RESULT_HASH_V1"
        private const val SCHEMA_V2 = "TRADING_NORMALIZATION_RESULT_V2"
        private const val HASH_V2 = "TRADING_NORMALIZATION_RESULT_HASH_V2"
        val V1 = NormalizationResultVersionPin(SCHEMA_V1, HASH_V1)
        val V2 = NormalizationResultVersionPin(SCHEMA_V2, HASH_V2)

        fun isSupported(schemaVersion: String, hashSpecificationVersion: String): Boolean =
            (schemaVersion == SCHEMA_V1 && hashSpecificationVersion == HASH_V1) ||
                (schemaVersion == SCHEMA_V2 && hashSpecificationVersion == HASH_V2)
    }
}
