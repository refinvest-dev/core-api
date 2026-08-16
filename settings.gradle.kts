pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "refinvest"

include(
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
