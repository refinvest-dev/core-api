package com.refinvest.core.auth.port.inbound.logout

import java.util.UUID

data class LogoutCommand(val familyId: UUID)
