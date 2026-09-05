package veclite.persistence.postgres;

import com.zaxxer.hikari.HikariDataSource;
import veclite.config.VectorLiteProperties;

import javax.sql.DataSource;

/**
 * Postgres 持久化组件的内置数据源工厂。
 * <p>
 * 作为 SDK 内嵌的持久化组件，不复用业务方的 {@code spring.datasource}，
 * 避免与应用主数据源的事务管理、连接池配置相互干扰。
 * 向量仓储与 Embedding 模型存储必须共享同一数据源实例——
 * Spring 装配路径由自动配置的 {@code veclitePostgresDataSource} Bean 提供（Hikari 连接池，
 * 容器销毁时推断调用 close()）；仅独立构造（脱离 Spring）时各自建池兜底。
 */
public final class PostgresDataSources {

    /** 内嵌 SDK 组件保持低常驻水位：空闲时仅保留少量热连接，写入高峰弹性扩到 Hikari 默认 maxPoolSize=10 */
    private static final int MINIMUM_IDLE_CONNECTIONS = 2;

    private PostgresDataSources() {
    }

    /**
     * 创建 Hikari 连接池数据源（惰性建连，首个 getConnection 时才初始化池）。
     * 池参数除 minimumIdle 外取 Hikari 默认值（maxPoolSize=10），仅标注 poolName 便于监控定位。
     */
    public static DataSource createPooledDataSource(VectorLiteProperties properties) {
        VectorLiteProperties.PostgresConfig config = properties.getStorage().getPostgres();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(config.getJdbcUrl());
        dataSource.setUsername(config.getUsername());
        dataSource.setPassword(config.getPassword());
        dataSource.setPoolName("veclite-postgres");
        dataSource.setMinimumIdle(MINIMUM_IDLE_CONNECTIONS);
        return dataSource;
    }
}
