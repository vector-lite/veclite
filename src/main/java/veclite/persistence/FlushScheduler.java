package veclite.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import veclite.engine.LocalVectorEngine;
import veclite.engine.LocalVectorStore;

import javax.annotation.PreDestroy;
import java.util.List;

/**
 * 定时刷盘调度器：每 N 秒把内存中所有 store 同步到持久化后端。
 * <p>
 * 解决"upsert 后没主动 saveStore → 进程崩溃丢一个刷盘窗口的数据"的风险。
 * 对 {@link StorageType#SNAPSHOT_FILE} 这类"内存为准、定期落盘"的后端是必需的；
 * 对单一真相源后端（MONGODB / POSTGRES）写入即持久化，本调度器退化为一次幂等对账，
 * 用于修复真相源中可能存在的漂移，开销可控。
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>固定间隔（默认 30s）遍历 {@link LocalVectorEngine#listStores} 拿到所有 store</li>
 *   <li>逐个调 {@link VectorPersistenceStorage#saveStore} 落盘</li>
 *   <li>单个 store 失败不抛 — 内存数据保留，下次再尝试</li>
 * </ul>
 *
 * <h3>并发</h3>
 * <p>各实现的 {@code saveStore} 自身是 {@code synchronized} 的，且落盘是 CPU + IO 密集型，
 * 单线程顺序刷即可；多线程并发刷同一个 store 反而会互相拖慢。
 */
@Component
public class FlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlushScheduler.class);

    private final LocalVectorEngine engine;
    private final VectorPersistenceStorage persistence;
    private final boolean flushOnShutdown;

    @Autowired
    public FlushScheduler(LocalVectorEngine engine, VectorPersistenceStorage persistence,
                          @Value("${veclite.storage.snapshot-file.flush-on-shutdown:true}")
                          boolean flushOnShutdown) {
        this.engine = engine;
        this.persistence = persistence;
        this.flushOnShutdown = flushOnShutdown;
    }

    /**
     * 兜底构造器：bean 不可用时 no-op，保证非 Spring 场景（单元测试、纯 SDK 用法）也能实例化。
     */
    public FlushScheduler() {
        this.engine = null;
        this.persistence = null;
        this.flushOnShutdown = false;
    }

    @Scheduled(
            fixedDelayString = "${veclite.storage.snapshot-file.flush-interval-seconds:30}000",
            initialDelayString = "${veclite.storage.snapshot-file.flush-interval-seconds:30}000"
    )
    public void flushAll() {
        if (engine == null || persistence == null) {
            log.debug("FlushScheduler skipped: engine={}, persistence={}", engine, persistence);
            return;
        }
        List<String> storeNames = engine.listStores();
        if (storeNames.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        int success = 0;
        int failed = 0;
        for (String storeName : storeNames) {
            try {
                LocalVectorStore store = engine.getStore(storeName);
                persistence.saveStore(store);
                success++;
            } catch (Exception e) {
                failed++;
                log.error("Scheduled flush failed for store [{}]: {}", storeName, e.getMessage());
            }
        }
        long cost = System.currentTimeMillis() - start;
        if (success > 0 || failed > 0) {
            log.info("Scheduled flush done in {}ms: success={}, failed={}", cost, success, failed);
        }
    }

    /**
     * 容器关闭时同步刷盘：补齐定时窗口外的最后一击。
     * <p>受 {@code veclite.storage.snapshot-file.flush-on-shutdown} 控制（默认 true）。
     * <p>注意：K8s 滚动更新时容器在 {@code terminationGracePeriodSeconds}（默认 30s）内被强杀，
     * 本方法必须在这段时间内完成；否则需要配合 preStop hook sleep 把数据写入窗口拉长。
     */
    @PreDestroy
    public void flushOnShutdown() {
        if (!flushOnShutdown) {
            log.info("FlushScheduler: flush-on-shutdown=false, skipping pre-destroy flush");
            return;
        }
        if (engine == null || persistence == null) {
            return;
        }
        log.info("FlushScheduler: shutdown detected, performing final flush...");
        try {
            flushAll();
        } catch (Exception e) {
            // 关闭路径上不让异常逃逸，避免 Tomcat shutdown 报错的连锁反应
            log.error("Failed to perform shutdown flush (memory data may be lost): {}", e.getMessage(), e);
        }
    }
}
