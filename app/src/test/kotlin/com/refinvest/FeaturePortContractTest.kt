package com.refinvest

import com.refinvest.core.member.port.inbound.create.CreateMemberUseCase
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import com.refinvest.core.subscription.port.inbound.create.CreateSubscriptionUseCase
import com.refinvest.core.subscription.port.inbound.policy.GetBacktestPolicyUseCase
import com.refinvest.core.strategy.port.inbound.owner.LookupStrategyOwnerUseCase
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import com.refinvest.core.backtest.port.inbound.usage.GetMonthlyBacktestUsageUseCase
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertTrue

class FeaturePortContractTest {
    @Test
    fun crossFeatureContractsDoNotExposeDomainTypesThroughTheirSignaturesOrNestedModels() {
        val inspected = mutableSetOf<Type>()
        fun inspect(type: Type) {
            if (!inspected.add(type)) return
            when (type) {
                is ParameterizedType -> { inspect(type.rawType); type.actualTypeArguments.forEach(::inspect) }
                is WildcardType -> (type.upperBounds + type.lowerBounds).forEach(::inspect)
                is Class<*> -> {
                    if (type.isArray) { inspect(type.componentType); return }
                    val name = type.name
                    if (type.isPrimitive || name.startsWith("java.") || name.startsWith("kotlin.")) return
                    assertTrue(name.startsWith("com.refinvest.core.shared.kernel.") ||
                        Regex("com\\.refinvest\\.core\\.[a-z]+\\.port\\.").containsMatchIn(name), "Domain or implementation escaped through a feature contract: $name")
                    if (name.startsWith("com.refinvest.core.shared.kernel.") || type.isEnum) return
                    type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { inspect(it.genericType) }
                    type.declaredMethods.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
                        inspect(it.genericReturnType); it.genericParameterTypes.forEach(::inspect)
                    }
                    type.genericSuperclass?.let(::inspect)
                    type.genericInterfaces.forEach(::inspect)
                    if (type.isSealed) type.permittedSubclasses.forEach(::inspect)
                }
            }
        }
        listOf(CreateMemberUseCase::class.java, GetMemberUseCase::class.java, CreateSubscriptionUseCase::class.java,
            GetBacktestPolicyUseCase::class.java, LookupStrategyOwnerUseCase::class.java,
            LookupStrategyVersionForBacktestUseCase::class.java, GetMonthlyBacktestUsageUseCase::class.java).forEach(::inspect)
    }
}
