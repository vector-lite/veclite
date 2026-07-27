package com.hexin.vector.lite.config;

import com.hexin.vector.lite.api.EmbeddingProvider;
import com.hexin.vector.lite.api.VectorEngineClient;
import com.hexin.vector.lite.api.VectorStoreManager;
import com.hexin.vector.lite.embedding.HttpEmbeddingProvider;
import com.hexin.vector.lite.engine.LocalVectorEngine;
import com.hexin.vector.lite.engine.VectorEngineClientImpl;
import com.hexin.vector.lite.model.StorageType;
import com.hexin.vector.lite.persistence.NoopVectorPersistenceStorage;
import com.hexin.vector.lite.persistence.SnapshotFileStorage;
import com.hexin.vector.lite.persistence.VectorPersistenceStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "aime.vector.lite.enabled", havingValue = "true", matchIfMissing = true)
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
