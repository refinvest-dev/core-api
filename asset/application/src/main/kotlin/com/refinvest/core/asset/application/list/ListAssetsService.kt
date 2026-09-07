package com.refinvest.core.asset.application.list

import com.refinvest.core.asset.domain.policy.MvpAssetUniverse
import com.refinvest.core.asset.port.inbound.list.ListAssetsResult
import com.refinvest.core.asset.port.inbound.list.ListAssetsUseCase
import com.refinvest.core.asset.port.outbound.compute.AssetDataClient
import org.springframework.stereotype.Service

@Service
class ListAssetsService(
    private val assetDataClient: AssetDataClient,
) : ListAssetsUseCase {
    override fun execute(): ListAssetsResult = ListAssetsResult(
        assetDataClient.listAssets().filter { asset -> MvpAssetUniverse.contains(asset.symbol) },
    )
}
