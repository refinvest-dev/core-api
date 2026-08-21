pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "refinvest"

include(
    ":member:domain",
    ":member:port",
    ":member:application",
    ":member:adapter:persistence",
    ":member:adapter:snowflake",
    ":subscription:domain",
    ":subscription:port",
    ":subscription:application",
    ":subscription:adapter:persistence",
    ":auth:domain",
    ":auth:port",
    ":auth:application",
    ":auth:adapter:persistence",
    ":auth:adapter:security",
    ":auth:adapter:web",
    ":strategy:domain",
    ":strategy:port",
    ":strategy:application",
    ":strategy:adapter:snowflake",
    ":strategy:adapter:web",
    ":strategy:adapter:persistence",
    ":backtest:domain",
    ":backtest:port",
    ":backtest:application",
    ":backtest:adapter:compute",
    ":backtest:adapter:snowflake",
    ":backtest:adapter:persistence",
    ":backtest:adapter:web",
    ":shared:kernel",
    ":shared:infrastructure",
    ":app",
)
