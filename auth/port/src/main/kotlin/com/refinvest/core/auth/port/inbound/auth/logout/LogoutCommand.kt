package com.refinvest.core.auth.port.inbound.auth.logout

import java.util.UUID

data class LogoutCommand(val familyId: UUID)
