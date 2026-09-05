package veclite.engine;

/**
 * 内部数据一致性断言工具（v2.4 § 4.4 "Fail-Fast 不变量断言检查"）。
 *
 * <p>校验 {@link LocalVectorStore} 内三个平行数据结构的实际条目数是否一致：
 * <ul>
 *   <li>向量缓冲区（{@code vectorBuffer.getSize()}）</li>
 *   <li>Payload 存储（{@code payloadStorage.getSize()}）</li>
 *   <li>ID 索引（{@code idOffsetIndex.size()}）</li>
 * </ul>
 *
 * <p>三者必须相等，否则说明在并发 Upsert / 异常回滚 / 逻辑 bug 路径上
 * 已经产生了错位状态。此时落盘只会把脏数据固化下来，
 * 启动加载后仍然是脏的——必须 Fail-Fast 阻止落盘。
 *
 * <p>调用时机：快照类持久化实现的 {@code flushSnapshot} 落盘前、启动装载到内存后。
 */
public final class LocalVectorStoreAssertions {

    private LocalVectorStoreAssertions() {
    }

    /**
     * 校验 store 内 vec / payload / idIndex 三者 size 一致。
     * <p>不一致时抛 {@link ConsistencyException}。
     *
     * @param store 待校验的 store（不能为 null）
     * @throws ConsistencyException 三者 size 不一致
     */
    public static void assertConsistency(LocalVectorStore store) {
        if (store == null) {
            return;
        }
        int vecSize = store.getVectorBufferSize();
        int payloadSize = store.getPayloadSize();
        int idIndexSize = store.getIdIndexSize();

        if (vecSize != payloadSize || payloadSize != idIndexSize) {
            String storeName = store.getDefinition() != null
                    ? store.getDefinition().getStoreName() : "<unknown>";
            throw new ConsistencyException(String.format(
                    "LocalVectorStore[%s] inconsistent: vecSize=%d, payloadSize=%d, idIndexSize=%d",
                    storeName, vecSize, payloadSize, idIndexSize));
        }
    }
}
