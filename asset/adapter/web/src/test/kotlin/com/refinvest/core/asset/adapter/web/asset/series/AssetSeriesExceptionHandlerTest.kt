package com.refinvest.core.asset.adapter.web.asset.series

import com.refinvest.core.asset.port.outbound.compute.SeriesDataErrorCode
import com.refinvest.core.asset.port.outbound.compute.SeriesDataErrorException
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetSeriesExceptionHandlerTest {
    private val handler = AssetSeriesExceptionHandler()

    @Test
    fun `preserves Compute series data error code in the public response`() {
        val response = handler.handle(
            SeriesDataErrorException(
                SeriesDataErrorCode.DATASET_CORRUPTION,
                "Required daily bar is missing.",
            ),
        )

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals(
            SeriesDataErrorResponse("Required daily bar is missing.", "DATASET_CORRUPTION"),
            response.body,
        )
    }
}
