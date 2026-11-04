package com.refinvest.core.tradingreview.adapter.persistence

import java.io.Serializable

data class DeletionCheckpointKey(var requestId: Long = 0, var component: String = "") : Serializable
