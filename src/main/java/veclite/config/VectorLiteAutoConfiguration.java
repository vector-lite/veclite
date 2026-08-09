package veclite.config;

import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreManager;
import veclite.embedding.HttpEmbeddingProvider;
import veclite.engine.LocalVectorEngine;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.StorageType;
import veclite.persistence.NoopVectorPersistenceStorage;
import veclite.persistence.SnapshotFileStorage;
import veclite.persistence.VectorPersistenceStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import veclite.web.VectorLiteDebugController;

/**
 * Veclite 向量 SDK Spring Boot 自动配置类。
 * <p>
 * 当 `veclite.enabled=true`（默认开启）时激活，
 * 向 Spring 容器注入 EmbeddingProvider、VectorPersistenceStorage、LocalVectorEngine 和 VectorEngineClient。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "veclite.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(VectorLiteProperties.class)
@Import(VectorLiteDebugController.class)
public class VectorLiteAutoConfiguration {

    /**
     * 默认的 HTTP Embedding 提供者组件
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingProvider embeddingProvider(VectorLiteProperties properties) {
        return new HttpEmbeddingProvider(properties);
    }

    /**
     * 根据配置文件类型 (`veclite.storage.type`) 装配持久化存储策略组件
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorPersistenceStorage vectorPersistenceStorage(VectorLiteProperties properties) {
        if (properties.getStorage().getType() == StorageType.SNAPSHOT_FILE) {
            return new SnapshotFileStorage(properties);
        }
        return new NoopVectorPersistenceStorage();
    }

    /**
     * 内存向量引擎核心实例
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalVectorEngine localVectorEngine(VectorLiteProperties properties) {
        return new LocalVectorEngine(properties);
    }

    /**
     * 向量 Store 管理组件
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorStoreManager vectorStoreManager(LocalVectorEngine localVectorEngine) {
        return localVectorEngine;
    }

    /**
     * 对外开放的核心操作客户端（Facade）
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorEngineClient vectorEngineClient(LocalVectorEngine localVectorEngine,
                                                 EmbeddingProvider embeddingProvider,
                                                 VectorPersistenceStorage vectorPersistenceStorage,
                                                 VectorLiteProperties properties) {
        return new VectorEngineClientImpl(localVectorEngine, embeddingProvider, vectorPersistenceStorage, properties);
    }
}
