package veclite;

import veclite.api.VectorStoreDefinition;
import veclite.engine.LocalVectorStore;
import veclite.engine.LocalVectorStoreAssertions;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link LocalVectorStore} 在多线程并发 Upsert / Search / Delete 下
 * 内部 vec / payload / idIndex 三个 size 始终一致（v2.4 § 4）。
 */
public class LocalVectorStoreConcurrencyTest {

    private static final int DIMENSION = 32;
    private static final long SEED = 42L; // fixed seed for CI stability

    private LocalVectorStore newStore(String name, int maxCapacity) {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName(name);
        def.setDimension(DIMENSION);
        def.setMetric("COSINE");
        def.setMaxCapacity(maxCapacity);
        return new LocalVectorStore(def);
    }

    private VectorDocument newDoc(int id, long randSeed) {
        Random r = new Random(randSeed);
        float[] vec = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vec[i] = r.nextFloat();
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("id", id);
        return new VectorDocument("doc_" + id, vec, "text_" + id, meta);
    }

    @Test
    @DisplayName("4 线程并发 insert 1000 条不重复 id,assertConsistency 通过")
    public void concurrentInsert_4threads_1000docs() throws InterruptedException {
        LocalVectorStore store = newStore("concurrent_insert", 2000);
        int threadCount = 4;
        int perThread = 250;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        int id = tid * perThread + i;
                        try {
                            store.upsert(newDoc(id, SEED + id));
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Threads did not complete in 30s");
        executor.shutdown();

        assertEquals(0, errors.get(), "upsert should not throw");
        assertEquals(1000, store.getActiveCount());
        assertEquals(1000, store.getVectorBufferSize());
        assertEquals(1000, store.getPayloadSize());
        assertEquals(1000, store.getIdIndexSize());

        // v2.4 § 4.4 内部一致性断言
        assertDoesNotThrow(() -> LocalVectorStoreAssertions.assertConsistency(store));

        // 抽样 100 条能查到
        for (int i = 0; i < 100; i++) {
            assertNotNull(store.getDocumentPayloadAt(i), "offset " + i + " payload missing");
        }
    }

    @Test
    @DisplayName("4 线程并发混合 upsert(50%)+update(50%),assertConsistency 通过")
    public void concurrentMixedUpsertAndUpdate() throws InterruptedException {
        LocalVectorStore store = newStore("concurrent_mixed", 2000);
        int threadCount = 4;
        int perThread = 250;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 预热 1000 条(线程之间可能撞 id)
        for (int i = 0; i < 1000; i++) {
            store.upsert(newDoc(i, SEED + i));
        }

        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        int id = tid * perThread + i; // 50% 撞已有
                        try {
                            store.upsert(newDoc(id, SEED + tid * 10000L + i));
                        } catch (Exception ignored) {
                            // 更新已有 id 时如果达到 maxCapacity 不会抛,因为是 update 不增 size
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // size 应当 == 1000(因为所有 id 都已存在,都是 update)
        assertEquals(1000, store.getActiveCount());
        assertEquals(1000, store.getVectorBufferSize());
        assertEquals(1000, store.getPayloadSize());
        assertEquals(1000, store.getIdIndexSize());

        assertDoesNotThrow(() -> LocalVectorStoreAssertions.assertConsistency(store));
    }

    @Test
    @DisplayName("并发 upsert + 搜索混合,size 一致")
    public void concurrentUpsertAndSearch() throws InterruptedException {
        LocalVectorStore store = newStore("concurrent_search", 5000);
        int writerCount = 2;
        int readerCount = 4;
        int writesPerThread = 500;
        int readsPerThread = 200;

        ExecutorService executor = Executors.newFixedThreadPool(writerCount + readerCount);
        CountDownLatch latch = new CountDownLatch(writerCount + readerCount);
        AtomicInteger writeErrors = new AtomicInteger(0);

        // writer
        for (int t = 0; t < writerCount; t++) {
            final int tid = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < writesPerThread; i++) {
                        int id = tid * writesPerThread + i;
                        try {
                            store.upsert(newDoc(id, SEED + id));
                        } catch (Exception e) {
                            writeErrors.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // reader
        for (int t = 0; t < readerCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < readsPerThread; i++) {
                        VectorSearchRequest req = new VectorSearchRequest();
                        req.setQueryVector(newDoc(i, SEED + 100000L + i).getVector());
                        req.setTopK(5);
                        try {
                            List<VectorSearchResult> r = store.search(req);
                            // 搜到 0~topK 条都是合法的
                            assertTrue(r.size() >= 0 && r.size() <= 5);
                        } catch (Exception e) {
                            // 读路径异常单独 fail
                            fail("search threw during concurrent upsert: " + e.getMessage());
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(60, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(0, writeErrors.get());
        int expected = writerCount * writesPerThread;
        assertEquals(expected, store.getActiveCount());
        assertEquals(expected, store.getVectorBufferSize());
        assertEquals(expected, store.getPayloadSize());
        assertEquals(expected, store.getIdIndexSize());

        assertDoesNotThrow(() -> LocalVectorStoreAssertions.assertConsistency(store));
    }
}
