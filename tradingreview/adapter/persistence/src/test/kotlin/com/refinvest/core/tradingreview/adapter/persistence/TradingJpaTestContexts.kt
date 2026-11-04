package com.refinvest.core.tradingreview.adapter.persistence

import jakarta.persistence.EntityManagerFactory
import java.sql.Connection
import java.util.IdentityHashMap
import javax.sql.DataSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.ConnectionProxy
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.PlatformTransactionManager

/** Real Spring Data/JPA adapters over each scenario's existing isolated PostgreSQL connection. */
internal object TradingJpaTestContexts {
    private val contexts = IdentityHashMap<Connection, AnnotationConfigApplicationContext>()

    @Synchronized
    fun context(source: DataSource): AnnotationConfigApplicationContext {
        require(source is SingleConnectionDataSource) { "Fixture requires its isolated existing connection" }
        val handle = source.connection
        val connection = (handle as? ConnectionProxy)?.targetConnection ?: handle
        return contexts.getOrPut(connection) {
            AnnotationConfigApplicationContext().apply {
                beanFactory.registerSingleton("dataSource", SingleConnectionDataSource(connection, true))
                register(TradingJpaFixtureConfiguration::class.java,
                    JpaTradingAccountStoreAdapter::class.java, JpaTradingAccountReaderAdapter::class.java,
                    JpaTradingBookStoreAdapter::class.java, JpaTradingBookReaderAdapter::class.java)
                refresh()
            }
        }
    }

    @Synchronized
    fun close() { contexts.values.forEach { it.close() }; contexts.clear() }
}

internal fun tradingTestTransactions(source: DataSource): PlatformTransactionManager =
    if (source is SingleConnectionDataSource) TradingJpaTestContexts.context(source).getBean(JpaTransactionManager::class.java)
    else DataSourceTransactionManager(source) // Independent SQL-only lease/skip-locked fixtures have no aggregate adapter.

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = [TradingAccountJpaStore::class])
internal class TradingJpaFixtureConfiguration {
    @Bean
    fun entityManagerFactory(dataSource: DataSource) = LocalContainerEntityManagerFactoryBean().apply {
        setDataSource(dataSource)
        setPackagesToScan(TradingAccountJpaEntity::class.java.packageName)
        jpaVendorAdapter = HibernateJpaVendorAdapter()
        setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "validate", "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect"))
    }

    @Bean
    fun transactionManager(entityManagerFactory: EntityManagerFactory) = JpaTransactionManager(entityManagerFactory)
}
