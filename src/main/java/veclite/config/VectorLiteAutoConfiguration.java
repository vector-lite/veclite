package veclite.config;

import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreManager;
import veclite.embedding.EmbeddingModelRegistry;
import veclite.embedding.EmbeddingModelStore;
import veclite.embedding.EmbeddingService;
import veclite.embedding.HttpEmbeddingProvider;
import veclite.engine.LocalVectorEngine;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.StorageType;
import veclite.persistence.NoopVectorPersistenceStorage;
import veclite.persistence.mongo.MongoEmbeddingModelStore;
import veclite.persistence.mongo.MongoVectorDocumentRepository;
import veclite.persistence.mongo.MongoVectorPersistenceStorage;
import veclite.persistence.SnapshotFileStorage;
import veclite.persistence.VectorPersistenceStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import veclite.web.VectorLiteDebugController;
import veclite.web.VectorLiteUiController;

/**
 * Veclite 向量 SDK Spring Boot 自动配置类。
 * <p>
 * 当 `veclite.enabled=true`（默认开启）时激活，
 * 向 Spring 容器注入 EmbeddingProvider、VectorPersistenceStorage、LocalVectorEngine 和 VectorEngineClient。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "veclite.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(VectorLiteProperties.class)
@Import({VectorLiteDebugController.class, VectorLiteUiController.class})
public class VectorLiteAutoConfiguration {

    /**
     * Embedding 模型配置注册中心（数据库维护）。
     * MONGODB 模式下持久化到 veclite_embedding_model 集合；其余模式仅内存生效（重启丢失）。
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingModelRegistry embeddingModelRegistry(VectorLiteProperties properties) {
        EmbeddingModelStore store = null;
        if (properties.getStorage().getType() == StorageType.MONGODB) {
            store = new MongoEmbeddingModelStore(properties);
        }
        return new EmbeddingModelRegistry(store);
    }

    /**
     * 默认的 HTTP Embedding 提供者组件（模型配置经注册中心解析）
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingProvider embeddingProvider(EmbeddingModelRegistry registry) {
        return new HttpEmbeddingProvider(registry);
    }

    /**
     * 根据配置文件类型 (`veclite.storage.type`) 装配持久化存储策略组件。
     * MongoDB 编排器持有底层连接，容器销毁时由 Spring 推断调用 close() 释放。
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorPersistenceStorage vectorPersistenceStorage(VectorLiteProperties properties) {
        StorageType type = properties.getStorage().getType();
        if (type == StorageType.SNAPSHOT_FILE) {
            return new SnapshotFileStorage(properties);
        }
        if (type == StorageType.MONGODB) {
            return new MongoVectorPersistenceStorage(
                    new MongoVectorDocumentRepository(properties), properties);
        }
        return new NoopVectorPersistenceStorage();
    }

    /**
     * Embedding 接口管理服务
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingService embeddingService(EmbeddingProvider embeddingProvider,
                                             EmbeddingModelRegistry registry) {
        return new EmbeddingService(embeddingProvider, registry);
    }

    /**
     * 内存向量引擎核心实例
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalVectorEngine localVectorEngine(VectorLiteProperties properties, EmbeddingService embeddingService) {
        return new LocalVectorEngine(properties, embeddingService);
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
                                                 VectorLiteProperties properties,
                                                 EmbeddingModelRegistry embeddingModelRegistry) {
        return new VectorEngineClientImpl(localVectorEngine, embeddingProvider,
                vectorPersistenceStorage, properties, embeddingModelRegistry);
    }
}
