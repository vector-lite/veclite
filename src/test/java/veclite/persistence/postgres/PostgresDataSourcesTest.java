package veclite.persistence.postgres;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.config.VectorLiteProperties;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 内置 Postgres 数据源工厂回归：Spring 装配路径与独立构造路径统一走 Hikari 连接池
 * （此前是各建一个无池的 DriverManagerDataSource，每次取连接都新建物理连接），
 * 并确认池参数取自 veclite.storage.postgres 配置。
 */
class PostgresDataSourcesTest {

    @Test
    @DisplayName("工厂创建的 Hikari 数据源携带配置的连接信息，未建连前可直接关闭")
    void createsPooledDataSourceFromConfig() {
        VectorLiteProperties properties = new VectorLiteProperties();
        properties.getStorage().getPostgres().setJdbcUrl("jdbc:postgresql://localhost:5432/veclite");
        properties.getStorage().getPostgres().setUsername("veclite");
        properties.getStorage().getPostgres().setPassword("secret");

        DataSource dataSource = PostgresDataSources.createPooledDataSource(properties);

        HikariDataSource hikari = assertInstanceOf(HikariDataSource.class, dataSource);
        assertEquals("jdbc:postgresql://localhost:5432/veclite", hikari.getJdbcUrl());
        assertEquals("veclite", hikari.getUsername());
        assertEquals("veclite-postgres", hikari.getPoolName());
        // 惰性建连：未执行任何查询前关闭不应抛异常
        hikari.close();
    }
}
