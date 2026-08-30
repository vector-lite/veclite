package veclite.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.config.VectorLiteProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Embedding 模型配置注册中心单元测试：（名称， 版本）复合唯一键、
 * 主版本解析、默认标记与持久化写穿。
 */
class EmbeddingModelRegistryTest {

    /** 内存版持久化端口，模拟 MongoDB 集合（（name, version）唯一） */
    private static final class InMemoryStore implements EmbeddingModelStore {
        final Map<String, VectorLiteProperties.ModelConfig> rows = new LinkedHashMap<>();
        EmbeddingModelRef defaultRef;

        @Override
        public List<VectorLiteProperties.ModelConfig> loadAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public void save(VectorLiteProperties.ModelConfig config) {
            rows.put(config.getName() + "\u001F" + config.getVersion(), config);
        }

        @Override
        public boolean delete(String name, String version) {
            return rows.remove(name + "\u001F" + version) != null;
        }

        @Override
        public void saveDefault(EmbeddingModelRef ref) {
            this.defaultRef = ref;
        }

        @Override
        public EmbeddingModelRef loadDefault() {
            return defaultRef;
        }
    }

    private static VectorLiteProperties.ModelConfig config(String name, String version, String url) {
        VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
        config.setName(name);
        config.setVersion(version);
        config.setProvider("ollama");
        config.setUrl(url);
        return config;
    }

    @Test
    @DisplayName("同名多版本：同名称不同版本可共存，互不覆盖")
    void sameNameDifferentVersionsShouldCoexist() {
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(new InMemoryStore());
        registry.save(config("m", "1", "http://a/v1"));
        registry.save(config("m", "2", "http://a/v2"));

        assertEquals(2, registry.effectiveList().size());
        assertEquals("http://a/v1", registry.find("m", "1").getUrl());
        assertEquals("http://a/v2", registry.find("m", "2").getUrl());
    }

    @Test
    @DisplayName("同（名称， 版本）重复保存为覆盖 upsert")
    void sameNameAndVersionShouldUpsert() {
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(new InMemoryStore());
        registry.save(config("m", "1", "http://a/old"));
        registry.save(config("m", "1", "http://a/new"));

        assertEquals(1, registry.effectiveList().size());
        assertEquals("http://a/new", registry.find("m", "1").getUrl());
    }

    @Test
    @DisplayName("主版本解析：未指定版本时取该名称第一个版本，默认标记指定的版本优先")
    void primaryVersionShouldFollowDefaultMarkerThenInsertionOrder() {
        InMemoryStore store = new InMemoryStore();
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(store);
        registry.save(config("m", "1", "http://a/v1"));
        registry.save(config("m", "2", "http://a/v2"));

        assertEquals("1", registry.primaryVersion("m"));
        assertEquals("http://a/v1", registry.find("m", null).getUrl());

        registry.saveDefault("m", "2");
        assertEquals("2", registry.primaryVersion("m"));
        assertEquals("http://a/v2", registry.find("m", null).getUrl());
        assertEquals("m", registry.defaultRef().name());
        assertEquals("2", registry.defaultRef().version());
    }

    @Test
    @DisplayName("delete 按名称+版本精确删除；默认标记随默认模型删除而清除")
    void deleteShouldBeExactByVersion() {
        InMemoryStore store = new InMemoryStore();
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(store);
        registry.save(config("m", "1", "http://a/v1"));
        registry.save(config("m", "2", "http://a/v2"));
        registry.saveDefault("m", "1");

        registry.delete("m", "1");
        assertNull(registry.find("m", "1"));
        assertEquals("http://a/v2", registry.find("m", "2").getUrl());
        assertNull(registry.defaultRef(), "删除默认模型后标记应清除");

        assertThrows(IllegalArgumentException.class, () -> registry.delete("m", "9"));
        assertThrows(IllegalArgumentException.class, () -> registry.delete("ghost", "1"));
    }

    @Test
    @DisplayName("save 应写穿持久化端口，reload 后配置与默认标记仍生效")
    void saveShouldPersistAndSurviveReload() {
        InMemoryStore store = new InMemoryStore();
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(store);
        registry.save(config("new-model", "2", "http://a/embeddings"));
        registry.saveDefault("new-model", "2");

        EmbeddingModelRegistry restarted = new EmbeddingModelRegistry(store);
        assertEquals("http://a/embeddings", restarted.find("new-model", "2").getUrl());
        assertEquals("new-model", restarted.defaultRef().name());
        assertEquals("2", restarted.defaultRef().version());
    }

    @Test
    @DisplayName("save 校验：名称与 url 必填，非法 provider 拒绝，空版本/批量自动补默认")
    void saveShouldValidateAndNormalize() {
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(new InMemoryStore());

        VectorLiteProperties.ModelConfig noUrl = config("m", "1", null);
        assertThrows(IllegalArgumentException.class, () -> registry.save(noUrl));

        VectorLiteProperties.ModelConfig badProvider = config("m", "1", "http://a/embeddings");
        badProvider.setProvider("bogus");
        assertThrows(IllegalArgumentException.class, () -> registry.save(badProvider));

        VectorLiteProperties.ModelConfig minimal = config("m", null, "http://a/embeddings");
        registry.save(minimal);
        VectorLiteProperties.ModelConfig saved = registry.find("m", "1");
        assertEquals("ollama", saved.getProvider());
        assertEquals(1000, saved.getTimeoutMillis());
        assertEquals(1, saved.getBatchSize());
        assertEquals("1", saved.getVersion());
    }

    @Test
    @DisplayName("无持久化端口时注册中心退化为纯内存")
    void registryWithoutStoreShouldWorkInMemory() {
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(null);
        assertEquals(0, registry.effectiveList().size());

        registry.save(config("memory-only", "1", "http://a/embeddings"));
        assertEquals(1, registry.effectiveList().size());
        assertEquals("http://a/embeddings", registry.find("memory-only", "1").getUrl());
    }
}
