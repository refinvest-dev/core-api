package com.refinvest.core.asset.adapter.web.asset.list

import com.refinvest.core.asset.port.inbound.list.ListAssetsResult

data class AssetListResponse(val assets: List<AssetResponse>) {
    companion object {
        fun from(result: ListAssetsResult) = AssetListResponse(result.assets.map(AssetResponse::from))
    }
}
