package veclite.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import veclite.api.VectorStoreDefinition;
import veclite.engine.ConsistencyException;
import veclite.engine.LocalVectorEngine;
import veclite.engine.LocalVectorStore;
import veclite.engine.LocalVectorStoreAssertions;

import java.util.List;

/**
 * OSS 启动加载器：在 Spring Boot 启动完成后阻塞地把 OSS 上的所有 store 加载到内存。
 * <p>这是 K8s 漂移 / Pod 重启后数据不丢的关键组件——新 Pod 起来时如果本地 mmap 缓存为空，
 * 自动从 OSS 拉回定义和向量数据。
 *
 * <h3>两步加载流程（与 {@link VectorPersistenceStorage#loadStore} 的签名约束匹配）</h3>
 * <ol>
 *   <li>调 {@link VectorPersistenceStorage#loadStoreDefinition} 拿 {@link VectorStoreDefinition}
 *       （拿到 dimension / metric / quantization 等元信息）</li>
 *   <li>如果 {@link LocalVectorEngine} 已存在这个 store（yml 里预配的）→ 复用；否则
 *       {@link LocalVectorEngine#createStore} 创建内存骨架</li>
 *   <li>调 {@link VectorPersistenceStorage#loadStore} 把向量数据反序列化进 LocalVectorStore</li>
 * </ol>
 * 不能跳过第 1 步直接调 loadStore，因为 loadStore 要求传入的 store 已存在且 definition 完整。
 *
 * <h3>激活条件</h3>
 * <p>仅在 {@link VectorPersistenceStorage} 是 {@link OssSnapshotStorage} 时才激活；
 * 其他实现（SnapshotFileStorage / Noop）不做事。
 */
@Component
@Order(0)  // 优先级最高：必须在所有依赖 store 列表的组件之前完成
public class OssStartupLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OssStartupLoader.class);

    private final VectorPersistenceStorage persistence;
    private final LocalVectorEngine engine;

    @org.springframework.beans.factory.annotation.Autowired
    public OssStartupLoader(VectorPersistenceStorage persistence, LocalVectorEngine engine) {
        this.persistence = persistence;
        this.engine = engine;
    }

    /**
     * No-arg 构造器作为兜底：如果 VectorPersistenceStorage / LocalVectorEngine 不可用时，
     * 仍然能创建 bean（run() 时会 no-op）。
     */
    public OssStartupLoader() {
        this.persistence = null;
        this.engine = null;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (persistence == null || engine == null) {
            log.info("OssStartupLoader skipped: persistence={}, engine={}",
                    persistence == null ? "null" : "ok",
                    engine == null ? "null" : "ok");
            return;
        }
        log.info("OssStartupLoader activated, persistence type = {}",
                persistence.getClass().getName());

        // 仅在 OSS 模式下做启动加载
        if (!(persistence instanceof OssSnapshotStorage)) {
            log.info("Skipping OSS startup loader: persistence is {}, not OssSnapshotStorage",
                    persistence.getClass().getSimpleName());
            return;
        }
        OssSnapshotStorage ossStorage = (OssSnapshotStorage) persistence;

        long start = System.currentTimeMillis();
        List<String> storeNames;
        try {
            storeNames = persistence.listStoreNames();
            log.info("OSS startup loader: persistence.listStoreNames() returned {} entries: {}",
                    storeNames.size(), storeNames);
        } catch (Exception e) {
            log.error("Failed to list store names from OSS, startup loader aborted: {}",
                    e.getMessage(), e);
            return;
        }

        if (storeNames.isEmpty()) {
            log.warn("No stores found on OSS. If you expect data, check: (1) endpoint/bucket/key-prefix env vars, (2) does keyPrefix='veclite/' actually contain your store directories? Startup loader done in {}ms",
                    System.currentTimeMillis() - start);
            return;
        }

        log.info("OSS startup loader found {} store(s): {}", storeNames.size(), storeNames);

        int success = 0;
        int skipped = 0;
        int failed = 0;
        int fromLocal = 0;
        int fromOss = 0;

        for (String storeName : storeNames) {
            try {
                // 第 0 步：本地优先 —— 如果本地 mmap 缓存已有完整快照，直接用本地
                if (ossStorage.hasLocalSnapshot(storeName)) {
                    try {
                        VectorStoreDefinition def = readStoreJsonFromLocal(ossStorage, storeName);
                        if (def == null) {
                            log.warn("Store [{}] local files present but store.json unreadable, falling back to OSS",
                                    storeName);
                        } else {
                            // 创建/复用内存骨架
                            LocalVectorStore store = ensureStoreInMemory(storeName, def);
                            // 从本地反序列化
                            ossStorage.loadStoreFromLocal(store);
                            // 加载后做一次内部一致性断言（v2.4 § 4.4）：
                            // 脏数据拒绝带病运行——打 ERROR 跳过该 store,不抛(否则启动失败)
                            try {
                                LocalVectorStoreAssertions.assertConsistency(store);
                            } catch (ConsistencyException ce) {
                                log.error("Store [{}] loaded from LOCAL but inconsistent: {}. " +
                                                "The store is skipped (no in-memory data). " +
                                                "Please investigate and clean local cache manually.",
                                        storeName, ce.getMessage());
                                failed++;
                                skipped++;
                                continue;
                            }
                            success++;
                            fromLocal++;
                            log.info("Store [{}] loaded from LOCAL mmap cache (skip OSS)", storeName);
                            continue;
                        }
                    } catch (Exception localEx) {
                        log.warn("Store [{}] local cache load failed ({}), falling back to OSS",
                                storeName, localEx.getMessage());
                    }
                }

                // 第 1 步：拿定义
                VectorStoreDefinition definition = persistence.loadStoreDefinition(storeName);
                if (definition == null) {
                    log.warn("Store [{}] has no store.json on OSS, skipping", storeName);
                    skipped++;
                    continue;
                }

                // 第 2 步：找到或创建 store
                LocalVectorStore store = ensureStoreInMemory(storeName, definition);

                // 第 3 步：从 OSS 拉向量数据（OssSnapshotStorage.saveStore 写过本地缓存，
                //         所以下次重启时会走"本地优先"分支）
                persistence.loadStore(store);
                // 加载后做一次内部一致性断言（v2.4 § 4.4）
                try {
                    LocalVectorStoreAssertions.assertConsistency(store);
                } catch (ConsistencyException ce) {
                    log.error("Store [{}] loaded from OSS but inconsistent: {}. " +
                                    "The store is skipped (no in-memory data). " +
                                    "Please investigate OSS source manually.",
                            storeName, ce.getMessage());
                    failed++;
                    skipped++;
                    continue;
                }
                success++;
                fromOss++;
                log.info("Store [{}] loaded from OSS", storeName);

            } catch (Exception e) {
                failed++;
                log.error("Failed to load store [{}]: {}", storeName, e.getMessage(), e);
            }
        }

        log.info("OSS startup loader done in {}ms: total={}, success={} (local={}, oss={}), skipped={}, failed={}",
                System.currentTimeMillis() - start, storeNames.size(), success, fromLocal, fromOss, skipped, failed);
    }

    private LocalVectorStore ensureStoreInMemory(String storeName, VectorStoreDefinition definition) {
        LocalVectorStore store;
        if (engine.listStores().contains(storeName)) {
            store = engine.getStore(storeName);
            log.info("Store [{}] already exists in memory, will refresh data", storeName);
        } else {
            engine.createStore(storeName, definition);
            store = engine.getStore(storeName);
            log.info("Store [{}] created from definition (dim={}, metric={}, quant={})",
                    storeName, definition.getDimension(), definition.getMetric(),
                    definition.getQuantization());
        }
        return store;
    }

    /**
     * 仅从本地 store.json 读取 VectorStoreDefinition，用于"本地优先"分支避免 OSS 调用。
     */
    private VectorStoreDefinition readStoreJsonFromLocal(OssSnapshotStorage ossStorage, String storeName) {
        try {
            java.io.File dir = ossStorage.getLocalStoreDir(storeName);
            java.io.File sj = new java.io.File(dir, "store.json");
            if (!sj.isFile()) return null;
            byte[] data = java.nio.file.Files.readAllBytes(sj.toPath());
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(data, VectorStoreDefinition.class);
        } catch (Exception e) {
            return null;
        }
    }
}
