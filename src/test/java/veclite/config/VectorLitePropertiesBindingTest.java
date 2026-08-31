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
 * VectorLiteProperties 配置绑定回归测试：保证 YAML 中 mongodb 配置节能正确绑定
 * （曾经出现 uri 未绑定导致 Mongo 连接缺少鉴权凭据的问题）。
 */
class VectorLitePropertiesBindingTest {

    @Test
    @DisplayName("application.yml 中 storage.mongodb.* 与 storage.postgres.* 应完整绑定")
    void mongodbSectionShouldBindFromApplicationYaml() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        loaded.forEach(sources::addLast);

        VectorLiteProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("veclite", Bindable.of(VectorLiteProperties.class))
                .get();

        assertEquals(StorageType.MONGODB, properties.getStorage().getType());
        // yml 中的 uri 是无凭证占位（真实凭证经环境变量注入，见下个用例），此处验证占位值完整绑定
        assertEquals("mongodb://localhost:27017/veclite", properties.getStorage().getMongodb().getUri());
        assertEquals("veclite", properties.getStorage().getMongodb().getDatabase());
        assertEquals("veclite_document", properties.getStorage().getMongodb().getDocumentCollection());

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
