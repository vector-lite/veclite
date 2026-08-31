package veclite.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.LocalVectorStore;
import veclite.persistence.meta.VectorMetadataRepository;
import veclite.persistence.meta.VectorStoreMetadata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replica 同步调度器：定时对比 PG latestSnapshotVersion 与本地缓存版本，
 * 发现落后则从 OSS 拉新 snapshot reload。
 *
 * 仅在节点 role=replica 时启用（master 不需要自己同步自己）。
 */
@Component
@ConditionalOnBean(VectorMetadataRepository.class)
public class ReplicaSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReplicaSyncScheduler.class);

    private final LocalVectorEngine engine;
    private final VectorMetadataRepository metadataRepository;
    private final VectorPersistenceStorage persistence;
    private final VectorLiteProperties properties;

    /** 本地已知的 store → 版本号 */
    private final Map<String, String> localVersions = new HashMap<>();

    public ReplicaSyncScheduler(LocalVectorEngine engine,
                                VectorMetadataRepository metadataRepository,
                                VectorPersistenceStorage persistence,
                                VectorLiteProperties properties) {
        this.engine = engine;
        this.metadataRepository = metadataRepository;
        this.persistence = persistence;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${veclite.replica.sync-interval-seconds:10}000",
            initialDelayString = "${veclite.replica.sync-interval-seconds:10}000"
    )
    public void syncFromPg() {
        if (properties.getNode() != null && properties.getNode().isMaster()) {
            // master 不需要自己拉自己
            return;
        }
        if (engine == null) return;
        try {
            List<VectorStoreMetadata> all = metadataRepository.listAll();
            for (VectorStoreMetadata m : all) {
                String storeName = m.getStoreName();
                String remoteVersion = m.getLatestSnapshotVersion();
                if (remoteVersion == null) continue;

                String localVersion = localVersions.get(storeName);
                if (remoteVersion.equals(localVersion)) {
                    continue;  // 已是最新
                }
                if (!engine.hasStore(storeName)) {
                    // 还没建本地 store，跳过（启动 init 阶段会处理）
                    continue;
                }
                try {
                    LocalVectorStore store = engine.getStore(storeName);
                    store.reset();
                    persistence.loadStore(store);
                    localVersions.put(storeName, remoteVersion);
                    log.info("Replica synced store [{}] to version [{}]", storeName, remoteVersion);
                } catch (Exception e) {
                    log.error("Replica sync failed for store [{}]: {}", storeName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("ReplicaSyncScheduler tick failed: {}", e.getMessage());
        }
    }

    /** 暴露给其他模块（如 createStore 事件）强制更新版本 */
    public void markVersion(String storeName, String version) {
        if (version != null) localVersions.put(storeName, version);
    }
}
