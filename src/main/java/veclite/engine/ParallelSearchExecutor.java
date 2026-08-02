package veclite.engine;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 专门用于多线程向量并行搜索的全局隔离线程池与调度器。
 * 避免高并发搜索请求创建过多临时 Thread 或抢占 Spring Boot Tomcat 主线程。
 * @author zhaoyuanlu
 */
public class ParallelSearchExecutor {

    private static volatile ExecutorService executorService;

    /**
     * 获取全局搜索并行线程池。
     * @param targetThreads 配置的目标线程数
     */
    public static ExecutorService getExecutor(int targetThreads) {
        if (executorService == null) {
            synchronized (ParallelSearchExecutor.class) {
                if (executorService == null) {
                    int threads = Math.max(1, targetThreads);
                    AtomicInteger threadNumber = new AtomicInteger(1);
                    executorService = new ThreadPoolExecutor(
                            threads,
                            threads,
                            60L, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(1000),
                            r -> {
                                Thread t = new Thread(r, "veclite-search-worker-" + threadNumber.getAndIncrement());
                                t.setDaemon(true);
                                return t;
                            },
                            new ThreadPoolExecutor.CallerRunsPolicy()
                    );
                }
            }
        }
        return executorService;
    }

    /**
     * 优雅关闭线程池资源。
     */
    public static synchronized void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            executorService = null;
        }
    }
}
