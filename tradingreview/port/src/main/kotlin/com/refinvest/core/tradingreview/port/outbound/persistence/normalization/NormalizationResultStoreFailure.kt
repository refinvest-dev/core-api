package com.refinvest.core.tradingreview.port.outbound.persistence.normalization

class NormalizationResultStoreFailure(val safeCode: String) : RuntimeException(safeCode)
