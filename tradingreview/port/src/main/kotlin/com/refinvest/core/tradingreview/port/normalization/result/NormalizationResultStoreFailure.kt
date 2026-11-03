package com.refinvest.core.tradingreview.port.normalization.result

class NormalizationResultStoreFailure(val safeCode: String) : RuntimeException(safeCode)

/** Transactional result state. Calls are made within the application transaction template. */
