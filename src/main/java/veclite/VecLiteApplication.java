package veclite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;

/**
 * 应用入口。
 * <p>
 * 显式排除两个自动配置：
 * <ul>
 *   <li>{@link MongoAutoConfiguration}：Mongo 连接由 veclite 持久化层自行管理，不走 Spring 自动配置；</li>
 *   <li>{@link DataSourceAutoConfiguration}：JDBC 数据源由 {@code veclite.storage.postgres.*} 自建
 *       （PostgresVectorDocumentRepository 内部持有 DriverManagerDataSource），
 *       不使用 {@code spring.datasource.*}，排除后避免启动时因缺 url 报错。</li>
 * </ul>
 */
@SpringBootApplication(exclude = {MongoAutoConfiguration.class, DataSourceAutoConfiguration.class})
public class VecLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(VecLiteApplication.class, args);
    }
}

