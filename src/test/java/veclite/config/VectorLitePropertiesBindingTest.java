package veclite.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import veclite.model.StorageType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VectorLiteProperties 配置绑定回归测试：保证 YAML 中数据库配置节点正确绑定。
 */
class VectorLitePropertiesBindingTest {

    @Test
    @DisplayName("application.yml 中数据库配置节应完整绑定")
    void databaseSectionsShouldBindFromApplicationYaml() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        loaded.forEach(sources::addLast);

        VectorLiteProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("veclite", Bindable.of(VectorLiteProperties.class))
                .get();

        // type 的具体取值由部署环境选择，这里验证绑定成功且为合法数据库后端；
        // 枚举非法值在绑定阶段即抛错，无需在此断言特定枚举
        StorageType boundType = properties.getStorage().getType();
        assertTrue(boundType == StorageType.MONGODB || boundType == StorageType.POSTGRES,
                "storage.type 应绑定为 MONGODB 或 POSTGRES, actual: " + boundType);
        // uri 由本地环境决定（凭证、库路径可能以任意组合出现），只做协议前缀断言；
        // 库名由独立的 database 配置承载，见下方断言
        String uri = properties.getStorage().getMongodb().getUri();
        assertTrue(uri.startsWith("mongodb://"), "Mongo uri 应形如 mongodb://..., actual: " + uri);
        assertEquals("veclite", properties.getStorage().getMongodb().getDatabase());

        // PostgreSQL 配置节同步验证绑定
        assertEquals("jdbc:postgresql://localhost:5432/veclite",
                properties.getStorage().getPostgres().getJdbcUrl());
        assertEquals("veclite_store_meta", properties.getStorage().getPostgres().getMetaTable());
        assertEquals(1000, properties.getStorage().getPostgres().getFetchSize());

        // 增量同步配置节绑定：必须落在 veclite.storage.sync 之下且 enabled 真为 true——
        // 若 sync 块缩进错位（挂在 veclite 之下），调度器 Bean 的 @ConditionalOnProperty
        // 将不满足，定时同步静默失效，此断言用于拦住该回归
        assertTrue(properties.getStorage().getSync().isEnabled(),
                "veclite.storage.sync.enabled 应在 application.yml 中绑定并开启");
        assertTrue(properties.getStorage().getSync().getIntervalSeconds() > 0);
        assertTrue(properties.getStorage().getSync().getRetentionDays() > 0);
    }

    @Test
    @DisplayName("Map 风格属性源应等价绑定（模拟 IDEA VM options / 环境变量覆盖场景）")
    void mapPropertySourceShouldBindEqually() {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("test", Map.of(
                "veclite.storage.type", "MONGODB",
                "veclite.storage.mongodb.uri", "mongodb://u:p@host:27017/?authSource=admin")));

        VectorLiteProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("veclite", Bindable.of(VectorLiteProperties.class))
                .get();

        assertEquals("mongodb://u:p@host:27017/?authSource=admin",
                properties.getStorage().getMongodb().getUri());
    }
}
