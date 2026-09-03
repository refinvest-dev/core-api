package com.refinvest.core.asset.adapter.web.asset.list

import com.refinvest.core.asset.domain.DataAvailability
import java.time.LocalDate

data class DataAvailabilityResponse(val firstDate: LocalDate, val lastDate: LocalDate) {
    companion object {
        fun from(availability: DataAvailability) = DataAvailabilityResponse(availability.firstDate, availability.lastDate)
    }
}
