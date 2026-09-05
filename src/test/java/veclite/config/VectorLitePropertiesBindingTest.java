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

        assertEquals(StorageType.POSTGRES, properties.getStorage().getType());
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
