package veclite.engine;

import java.io.Closeable;
import java.util.Map;

/**
 * 文档 Payload 存储层通用抽象接口。
 */
public interface PayloadStorage extends Closeable {

    /**
     * 写入或更新指定 offset 位置上的 Document Payload 数据。
     */
    void put(int offset, String id, String text, Map<String, Object> metadata);

    /**
     * 根据 offset 提取完整 DocumentPayload。
     */
    LocalVectorStore.DocumentPayload get(int offset);

    /**
     * 获取指定 offset 的 文档 ID。
     */
    String getId(int offset);

    /**
     * 获取指定 offset 的 文档 Text。
     */
    String getText(int offset);

    /**
     * 获取指定 offset 的 文档 Metadata。
     */
    Map<String, Object> getMetadata(int offset);

    /**
     * 当前已分配的 Payload 槽位数（容量，而非非空条目数）。
     * <p>用于 {@code assertConsistency} 与 vectorBuffer / idOffsetIndex 对账。
     */
    default int getSize() {
        return 0;
    }

    /**
     * 清空全部 Payload（reload 重置用）。
     */
    default void clear() {
        // 默认空实现
    }

    @Override
    default void close() {
        // 默认空实现
    }
}
