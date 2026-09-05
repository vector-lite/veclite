package veclite.engine;

import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreManager;
import veclite.config.VectorLiteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Store 增量同步调度器（文档型持久化专用）。
 * <p>
 * 多节点部署下，各节点经写透共享同一真相源，本调度器按固定间隔对每个已加载 Store
 * 执行 {@link VectorEngineClient#syncStore}——按水位拉取增量而非全量重载，
 * 单库失败只告警不中断其余库。自持调度线程，不依赖宿主应用的 @EnableScheduling。
 */
public class StoreSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(StoreSyncScheduler.class);

    /** 首轮同步的最大等待秒数：无论间隔多大，启动后尽快完成第一次增量收敛 */
    private static final long FIRST_TICK_MAX_DELAY_SECONDS = 5;

    private final VectorEngineClient client;
    private final VectorStoreManager storeManager;
    private final VectorLiteProperties properties;
    private ScheduledExecutorService executor;

    public StoreSyncScheduler(VectorEngineClient client,
                              VectorStoreManager storeManager,
                              VectorLiteProperties properties) {
        this.client = client;
        this.storeManager = storeManager;
        this.properties = properties;
    }

    /** 启动定时同步：首轮短暂延迟后尽快执行（新节点快速收敛、日志可见），此后按固定延迟循环 */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        int intervalSeconds = properties.getStorage().getSync().getIntervalSeconds();
        if (intervalSeconds <= 0) {
            intervalSeconds = 30;
        }
        long initialDelaySeconds = Math.min(intervalSeconds, FIRST_TICK_MAX_DELAY_SECONDS);
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "veclite-store-sync");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runOnceSafely, initialDelaySeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Store sync scheduler started: first tick in {}s, interval {}s", initialDelaySeconds, intervalSeconds);
    }

    /** 停止调度（Spring 容器销毁时调用） */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            log.info("Store sync scheduler stopped");
        }
    }

    /** 执行一轮同步：逐 Store 增量同步，单库失败记录告警后继续 */
    public void runOnce() {
        for (String storeName : storeManager.listStores()) {
            try {
                client.syncStore(storeName);
            } catch (Exception e) {
                log.warn("Incremental sync failed for store [{}]: {}", storeName, e.getMessage());
            }
        }
    }

    private void runOnceSafely() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Store sync round failed: {}", e.getMessage());
        }
    }
}
