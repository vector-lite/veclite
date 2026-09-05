package veclite.config;

import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreManager;
import veclite.embedding.EmbeddingModelRegistry;
import veclite.embedding.EmbeddingModelStore;
import veclite.embedding.EmbeddingService;
import veclite.embedding.HttpEmbeddingProvider;
import veclite.engine.LocalVectorEngine;
import veclite.engine.StoreSyncScheduler;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.StorageType;
import veclite.persistence.mongo.MongoEmbeddingModelStore;
import veclite.persistence.mongo.MongoVectorDocumentRepository;
import veclite.persistence.mongo.MongoVectorPersistenceStorage;
import veclite.persistence.postgres.PostgresDataSources;
import veclite.persistence.postgres.PostgresEmbeddingModelStore;
import veclite.persistence.postgres.PostgresVectorDocumentRepository;
import veclite.persistence.postgres.PostgresVectorPersistenceStorage;
import veclite.persistence.VectorPersistenceStorage;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import veclite.web.VectorLiteDebugController;
import veclite.web.VectorLiteUiController;

/**
 * Veclite 向量 SDK Spring Boot 自动配置类。
 * <p>
 * 当 `veclite.enabled=true`（默认开启）时激活，
 * 向 Spring 容器注入 EmbeddingProvider、VectorPersistenceStorage、LocalVectorEngine 和 VectorEngineClient。
 * <p>
 * 持久化后端由 {@code veclite.storage.type} 单点切换（MONGODB / POSTGRES）。
 * 数据库是生产环境唯一持久化路径；旧的文件快照和纯内存枚举值仅为源码兼容保留，
 * 不再由自动配置装配。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "veclite.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(VectorLiteProperties.class)
@Import({VectorLiteDebugController.class, VectorLiteUiController.class})
public class VectorLiteAutoConfiguration {

    /**
     * 共享 MongoDB 客户端（仅 type=MONGODB 时装配）：向量仓储与 Embedding 模型存储
     * 复用同一连接池，避免各自 MongoClients.create 导致启动即出现两条独立连接。
     * 容器销毁时由 Spring 推断调用 close() 释放；使用限定名注入以防宿主应用的
     * 其他 MongoClient Bean 产生歧义。
     */
    @Bean(name = "vecliteMongoClient", destroyMethod = "close")
    @ConditionalOnProperty(name = "veclite.storage.type", havingValue = "MONGODB")
    public MongoClient vecliteMongoClient(VectorLiteProperties properties) {
        return MongoClients.create(
                new ConnectionString(properties.getStorage().getMongodb().getUri()));
    }

    /**
     * 共享 PostgreSQL 数据源（仅 type=POSTGRES 时装配）：向量仓储与 Embedding 模型存储
     * 复用同一 Hikari 连接池——此前两者各建一个 {@code DriverManagerDataSource}（无池，
     * 每次取连接都新建物理连接）。容器销毁时由 Spring 推断调用 close() 释放；
     * 使用限定名注入以防宿主应用的其他 DataSource Bean 产生歧义。
     */
    @Bean(name = "veclitePostgresDataSource", destroyMethod = "close")
    @ConditionalOnProperty(name = "veclite.storage.type", havingValue = "POSTGRES")
    public DataSource veclitePostgresDataSource(VectorLiteProperties properties) {
        return PostgresDataSources.createPooledDataSource(properties);
    }

    /**
     * Embedding 模型配置注册中心（数据库维护）。
     * MONGODB / POSTGRES 模式下持久化到各自的模型表；其余模式仅内存生效（重启丢失）。
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingModelRegistry embeddingModelRegistry(VectorLiteProperties properties,
                                                         @Qualifier("vecliteMongoClient") ObjectProvider<MongoClient> mongoClient,
                                                         @Qualifier("veclitePostgresDataSource") ObjectProvider<DataSource> postgresDataSource) {
        StorageType type = properties.getStorage().getType();
        EmbeddingModelStore store = null;
        if (type == StorageType.MONGODB) {
            store = new MongoEmbeddingModelStore(mongoClient.getObject(), properties);
        } else if (type == StorageType.POSTGRES) {
            store = new PostgresEmbeddingModelStore(properties, postgresDataSource.getObject());
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
     * 文档型后端的编排器持有底层连接，容器销毁时由 Spring 推断调用 close() 释放。
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorPersistenceStorage vectorPersistenceStorage(VectorLiteProperties properties,
                                                             @Qualifier("vecliteMongoClient") ObjectProvider<MongoClient> mongoClient,
                                                             @Qualifier("veclitePostgresDataSource") ObjectProvider<DataSource> postgresDataSource) {
        StorageType type = properties.getStorage().getType();
        if (type == null) {
            throw new IllegalStateException("veclite.storage.type must be MONGODB or POSTGRES");
        }
        return switch (type) {
            case MONGODB -> new MongoVectorPersistenceStorage(
                    new MongoVectorDocumentRepository(mongoClient.getObject(), properties), properties);
            case POSTGRES -> new PostgresVectorPersistenceStorage(
                    new PostgresVectorDocumentRepository(properties, postgresDataSource.getObject()), properties);
            case NOOP, SNAPSHOT_FILE -> throw new IllegalStateException(
                    "Database persistence is required; unsupported storage type: " + type);
        };
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

    /**
     * Store 增量同步调度器：veclite.storage.sync.enabled=true 时启动，
     * 按固定间隔从真相源收敛内存投影（多节点部署用）。文档型持久化专用。
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "veclite.storage.sync.enabled", havingValue = "true")
    public StoreSyncScheduler storeSyncScheduler(VectorEngineClient vectorEngineClient,
                                                 VectorStoreManager vectorStoreManager,
                                                 VectorLiteProperties properties) {
        StoreSyncScheduler scheduler = new StoreSyncScheduler(vectorEngineClient, vectorStoreManager, properties);
        scheduler.start();
        return scheduler;
    }
}
