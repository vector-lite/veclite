package veclite;

import veclite.api.VectorStoreDefinition;
import veclite.engine.ConsistencyException;
import veclite.engine.LocalVectorStore;
import veclite.engine.LocalVectorStoreAssertions;
import veclite.model.VectorDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试 {@link LocalVectorStoreAssertions#assertConsistency} 的正确性。
 * <p>不依赖多线程,直接调各个 public getter 注入不同 size,验证断言能正确识别。
 */
public class LocalVectorStoreAssertionsTest {

    private static final int DIMENSION = 8;
    private static final long SEED = 100L;

    private LocalVectorStore newStore(String name) {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName(name);
        def.setDimension(DIMENSION);
        def.setMetric("COSINE");
        def.setMaxCapacity(1000);
        return new LocalVectorStore(def);
    }

    private void insertN(LocalVectorStore store, int n) {
        Random r = new Random(SEED);
        for (int i = 0; i < n; i++) {
            float[] vec = new float[DIMENSION];
            for (int d = 0; d < DIMENSION; d++) vec[d] = r.nextFloat();
            store.upsert(new VectorDocument("doc_" + i, vec, "text_" + i,
                    Map.of("seq", i)));
        }
    }

    @Test
    @DisplayName("空 store(0 条)通过断言")
    public void emptyStore_passes() {
        LocalVectorStore store = newStore("empty");
        assertDoesNotThrow(() -> LocalVectorStoreAssertions.assertConsistency(store));
    }

    @Test
    @DisplayName("插入 100 条后,三个 size 相等,通过断言")
    public void consistentStore_passes() {
        LocalVectorStore store = newStore("consistent");
        insertN(store, 100);
        assertEquals(100, store.getVectorBufferSize());
        assertEquals(100, store.getPayloadSize());
        assertEquals(100, store.getIdIndexSize());
        assertDoesNotThrow(() -> LocalVectorStoreAssertions.assertConsistency(store));
    }

    @Test
    @DisplayName("null store 不抛(静默跳过)")
    public void nullStore_silentlySkipped() {
        assertDoesNotThrow(() -> LocalVectorStoreAssertions.assertConsistency(null));
    }

    @Test
    @DisplayName("正常 store 反复调 100 次都通过")
    public void consistentStore_repeatedCheck() {
        LocalVectorStore store = newStore("repeated");
        insertN(store, 50);
        for (int i = 0; i < 100; i++) {
            assertDoesNotThrow(() -> LocalVectorStoreAssertions.assertConsistency(store));
        }
    }

    @Test
    @DisplayName("插入再删除后,size 仍然一致(只软删除不影响 size)")
    public void afterDelete_softDeleteOnly_sizeUnchanged() {
        LocalVectorStore store = newStore("after_delete");
        insertN(store, 50);
        // 删 10 条
        var ids = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> "doc_" + i)
                .collect(java.util.stream.Collectors.toList());
        store.deleteByIds(ids);
        // 软删除:vec/payload/idIndex size 不变
        assertEquals(50, store.getVectorBufferSize());
        assertEquals(50, store.getPayloadSize());
        assertEquals(50, store.getIdIndexSize());
        // activeCount 减 10
        assertEquals(40, store.getActiveCount());
        assertDoesNotThrow(() -> LocalVectorStoreAssertions.assertConsistency(store));
    }
}
