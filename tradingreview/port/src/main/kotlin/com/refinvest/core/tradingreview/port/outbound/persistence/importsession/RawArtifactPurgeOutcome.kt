package com.refinvest.core.tradingreview.port.outbound.persistence.importsession

import java.time.Instant

enum class RawArtifactPurgeOutcome { ABSENT, WAITING, RETRY, EXHAUSTED }
