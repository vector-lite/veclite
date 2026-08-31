package veclite.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreManager;
import veclite.embedding.EmbeddingService;
import veclite.embedding.HttpEmbeddingProvider;
import veclite.engine.LocalVectorEngine;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.StorageType;
import veclite.persistence.NoopVectorPersistenceStorage;
import veclite.persistence.OssSnapshotStorage;
import veclite.persistence.SnapshotFileStorage;
import veclite.persistence.VectorPersistenceStorage;
import veclite.persistence.meta.VectorMetadataRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
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
@EnableScheduling
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
     * OSS 客户端 Bean：仅在 storage.type=OSS 时创建。
     * <p>AK/SK 必须从环境变量读取（ALIBUN_OSS_ACCESS_KEY_ID / _SECRET）。
     * <p>连接 / 读超时从 {@code veclite.storage.oss.connect-timeout-ms / read-timeout-ms} 读取。
     */
    @Bean
    @ConditionalOnProperty(name = "veclite.storage.type", havingValue = "OSS")
    @ConditionalOnMissingBean
    public OSS ossClient(VectorLiteProperties properties) {
        VectorLiteProperties.OssConfig cfg = properties.getStorage().getOss();
        String endpoint = firstNonBlank(cfg.getEndpoint(), System.getenv("ALIYUN_OSS_ENDPOINT"));
        String ak = firstNonBlank(cfg.getAccessKeyId(), System.getenv("ALIYUN_OSS_ACCESS_KEY_ID"));
        String sk = firstNonBlank(cfg.getAccessKeySecret(), System.getenv("ALIYUN_OSS_ACCESS_KEY_SECRET"));
        if (endpoint == null || ak == null || sk == null) {
            throw new IllegalStateException(
                    "OSS 配置不完整。请在 yml 设置 veclite.storage.oss.{endpoint,bucket,access-key-id,access-key-secret} 或设置环境变量 ALIYUN_OSS_ENDPOINT / ALIYUN_OSS_ACCESS_KEY_ID / ALIYUN_OSS_ACCESS_KEY_SECRET");
        }
        ClientBuilderConfiguration clientCfg = new ClientBuilderConfiguration();
        clientCfg.setConnectionTimeout(cfg.getConnectTimeoutMs());
        clientCfg.setSocketTimeout(cfg.getReadTimeoutMs());
        return new OSSClientBuilder().build(endpoint, ak, sk, clientCfg);
    }

    /**
     * 根据配置文件类型 (`veclite.storage.type`) 装配持久化存储策略组件
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorPersistenceStorage vectorPersistenceStorage(
            VectorLiteProperties properties,
            @org.springframework.beans.factory.annotation.Autowired(required = false) OSS ossClient) {
        StorageType type = properties.getStorage().getType();
        if (type == StorageType.SNAPSHOT_FILE) {
            return new SnapshotFileStorage(properties);
        }
        if (type == StorageType.OSS) {
            if (ossClient == null) {
                throw new IllegalStateException(
                        "OSS Bean 未注入，请确认 application.yml 配了 veclite.storage.type=OSS");
            }
            return new OssSnapshotStorage(ossClient, properties);
        }
        return new NoopVectorPersistenceStorage();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    /**
     * Embedding 接口管理服务
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingService embeddingService(EmbeddingProvider embeddingProvider, VectorLiteProperties properties) {
        return new EmbeddingService(embeddingProvider, properties);
    }

    /**
     * 内存向量引擎核心实例
     * v2.4 hybrid persistence: 注入元数据仓储（PostgreSQL），createStore 时同步写 PG
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalVectorEngine localVectorEngine(VectorLiteProperties properties,
                                               EmbeddingService embeddingService,
                                               @org.springframework.beans.factory.annotation.Autowired(required = false) VectorMetadataRepository metadataRepository) {
        return new LocalVectorEngine(properties, embeddingService, metadataRepository);
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
                                                 @org.springframework.beans.factory.annotation.Autowired(required = false) VectorMetadataRepository metadataRepository) {
        return new VectorEngineClientImpl(localVectorEngine, embeddingProvider, vectorPersistenceStorage, properties, metadataRepository);
    }
}
