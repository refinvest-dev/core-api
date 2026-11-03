package com.refinvest.core.tradingreview.port.normalization.result

import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import java.time.Duration
import java.time.Instant

class NormalizationResultStoreFailure(val safeCode: String) : RuntimeException(safeCode)

/** Transactional result state. Calls are made within the application transaction template. */
