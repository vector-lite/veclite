package veclite.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于 磁盘 MMap / FileChannel 的 Payload 延迟加载存储层 (MMapPayloadStorage)。
 * <p>
 * 向量搜索主循环中完全不占用 JVM 堆内存存储 Text 和 Metadata 大对象。
 * 在 Upsert 时追加写入磁盘物理文件，在向量检索完成后仅对 Top-K offset 做按需延迟提取 (Lazy Fetch)。
 */
public class MMapPayloadStorage implements PayloadStorage {

    private final File dataFile;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    private RandomAccessFile raf;
    private FileChannel fileChannel;

    private String[] ids;
    private long[] filePositions;
    private int capacity;

    public MMapPayloadStorage(String storeName, String basePath, int initialCapacity) {
        this.capacity = Math.max(initialCapacity, 1024);
        this.ids = new String[capacity];
        this.filePositions = new long[capacity];
        Arrays.fill(this.filePositions, -1L);
        this.objectMapper = new ObjectMapper();

        try {
            File dir = new File(basePath, storeName);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            this.dataFile = new File(dir, "payload.mmap");
            this.raf = new RandomAccessFile(this.dataFile, "rw");
            this.fileChannel = raf.getChannel();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize MMapPayloadStorage for store [" + storeName + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public void put(int offset, String id, String text, Map<String, Object> metadata) {
        writeLock.lock();
        try {
            ensureCapacity(offset + 1);
            ids[offset] = id;

            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("id", id);
            payloadMap.put("text", text);
            payloadMap.put("metadata", metadata);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(payloadMap);
            long filePos = fileChannel.size();
            fileChannel.position(filePos);

            ByteBuffer buffer = ByteBuffer.allocate(4 + jsonBytes.length);
            buffer.putInt(jsonBytes.length);
            buffer.put(jsonBytes);
            buffer.flip();

            while (buffer.hasRemaining()) {
                fileChannel.write(buffer);
            }
            filePositions[offset] = filePos;
        } catch (Exception e) {
            throw new RuntimeException("Failed to put payload to MMap file at offset [" + offset + "]: " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public LocalVectorStore.DocumentPayload get(int offset) {
        readLock.lock();
        try {
            if (offset < 0 || offset >= capacity || filePositions[offset] < 0) {
                return null;
            }
            long pos = filePositions[offset];
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            fileChannel.read(lenBuf, pos);
            lenBuf.flip();
            if (lenBuf.remaining() < 4) {
                return null;
            }
            int jsonLen = lenBuf.getInt();
            ByteBuffer jsonBuf = ByteBuffer.allocate(jsonLen);
            fileChannel.read(jsonBuf, pos + 4);
            jsonBuf.flip();

            byte[] jsonBytes = new byte[jsonLen];
            jsonBuf.get(jsonBytes);

            Map<String, Object> map = objectMapper.readValue(jsonBytes, new TypeReference<Map<String, Object>>() {});
            String id = (String) map.get("id");
            String text = (String) map.get("text");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) map.get("metadata");

            return new LocalVectorStore.DocumentPayload(id, text, metadata);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read payload from MMap file at offset [" + offset + "]: " + e.getMessage(), e);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public String getId(int offset) {
        readLock.lock();
        try {
            return (offset >= 0 && offset < capacity) ? ids[offset] : null;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public String getText(int offset) {
        LocalVectorStore.DocumentPayload payload = get(offset);
        return payload != null ? payload.getText() : null;
    }

    @Override
    public Map<String, Object> getMetadata(int offset) {
        LocalVectorStore.DocumentPayload payload = get(offset);
        return payload != null ? payload.getMetadata() : null;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = capacity + (capacity >> 1);
            if (newCapacity < minCapacity) {
                newCapacity = minCapacity;
            }
            ids = Arrays.copyOf(ids, newCapacity);
            long[] newPos = Arrays.copyOf(filePositions, newCapacity);
            Arrays.fill(newPos, capacity, newCapacity, -1L);
            filePositions = newPos;
            capacity = newCapacity;
        }
    }

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (fileChannel != null) {
                fileChannel.close();
            }
            if (raf != null) {
                raf.close();
            }
        } catch (Exception ignored) {
        } finally {
            writeLock.unlock();
        }
    }
}
