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

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "veclite.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(VectorLiteProperties.class)
public class VectorLiteAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingProvider embeddingProvider(VectorLiteProperties properties) {
        return new HttpEmbeddingProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorPersistenceStorage vectorPersistenceStorage(VectorLiteProperties properties) {
        if (properties.getStorage().getType() == StorageType.SNAPSHOT_FILE) {
            return new SnapshotFileStorage(properties);
        }
        return new NoopVectorPersistenceStorage();
    }

    @Bean
    @ConditionalOnMissingBean
    public LocalVectorEngine localVectorEngine() {
        return new LocalVectorEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorStoreManager vectorStoreManager(LocalVectorEngine localVectorEngine) {
        return localVectorEngine;
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorEngineClient vectorEngineClient(LocalVectorEngine localVectorEngine,
                                                 EmbeddingProvider embeddingProvider,
                                                 VectorPersistenceStorage vectorPersistenceStorage,
                                                 VectorLiteProperties properties) {
        return new VectorEngineClientImpl(localVectorEngine, embeddingProvider, vectorPersistenceStorage, properties);
    }
}
