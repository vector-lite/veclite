package veclite.persistence;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.ConsistencyException;
import veclite.engine.LocalVectorStore;
import veclite.engine.LocalVectorStoreAssertions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于阿里云 OSS 的快照持久化实现。
 *
 * <h3>布局</h3>
 * <pre>
 * oss://{bucket}/{keyPrefix}/{storeName}/
 *   store.json
 *   vectors.bin
 *   documents.jsonl
 * </pre>
 *
 * <h3>写流程（双写）</h3>
 * <ol>
 *   <li>用 {@link SnapshotSerializer} 把 LocalVectorStore 序列化为 3 个 byte[]</li>
 *   <li>同时写本地 mmap 目录（LocalVectorStore 启动要 mmap 文件）</li>
 *   <li>3 个文件 PutObject 到 OSS（同名覆盖，原子）</li>
 *   <li>失败重试 3 次；重试都失败打 ERROR 日志但不抛异常（数据在内存里）</li>
 * </ol>
 *
 * <h3>读流程</h3>
 * <ol>
 *   <li>GetObject 3 个文件到本地 mmap 目录</li>
 *   <li>用 {@link SnapshotSerializer} 反序列化进 LocalVectorStore</li>
 * </ol>
 */
public class OssSnapshotStorage implements VectorPersistenceStorage {

    private static final Logger log = LoggerFactory.getLogger(OssSnapshotStorage.class);

    private static final String F_STORE_JSON = "store.json";
    private static final String F_VECTORS_BIN = "vectors.bin";
    private static final String F_DOCUMENTS_JSONL = "documents.jsonl";

    private final OSS oss;
    private final String bucket;
    private final String keyPrefix;
    private final VectorLiteProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OssSnapshotStorage(OSS oss, VectorLiteProperties properties) {
        this.oss = oss;
        this.properties = properties;
        this.bucket = properties.getStorage().getOss().getBucket();
        this.keyPrefix = normalizePrefix(properties.getStorage().getOss().getKeyPrefix());
    }

    private static String normalizePrefix(String p) {
        if (p == null || p.isEmpty()) return "";
        if (!p.endsWith("/")) return p + "/";
        return p;
    }

    private String storePrefix(String storeName) {
        return keyPrefix + storeName + "/";
    }

    // ----------------------------------------------------------------
    // saveStore
    // ----------------------------------------------------------------
    @Override
    public synchronized void saveStore(LocalVectorStore store) {
        if (store == null || store.getDefinition() == null) return;
        String storeName = store.getDefinition().getStoreName();
        // 落盘前做内部一致性断言（v2.4 § 4.4）：
        // 严格模式：直接抛 ConsistencyException，saveStore 失败（内存数据保留）
        // 非严格模式：打 ERROR 继续落盘
        try {
            LocalVectorStoreAssertions.assertConsistency(store);
        } catch (ConsistencyException ce) {
            if (properties.getConsistency().isStrict()) {
                throw ce;
            }
            log.error("Consistency check failed for store [{}] (non-strict, continuing): {}",
                    storeName, ce.getMessage());
        }
        // 生成下一个 snapshotVersion 写进 Blob.storeJson
        String nextVersion = nextSnapshotVersion(store.getDefinition().getSnapshotVersion());
        store.getDefinition().setSnapshotVersion(nextVersion);
        try {
            SnapshotSerializer.Blob blob = SnapshotSerializer.serialize(store);

            // 1. 写本地 mmap（让 LocalVectorStore 启动能读到 mmap 文件）
            writeLocalFiles(storeName, blob);

            // 2. 写 OSS（带重试）
            String prefix = storePrefix(storeName);
            final String p = prefix;
            retryWithBackoff("OSS saveStore", new ThrowingRunnable() {
                @Override
                public void run() throws Exception {
                    putBytes(p + F_STORE_JSON, blob.storeJson);
                    putBytes(p + F_VECTORS_BIN, blob.vectorsBin);
                    putBytes(p + F_DOCUMENTS_JSONL, blob.documentsJsonl);
                }
            });
            log.info("Saved store [{}] to OSS (version={}, {} bytes)", storeName, nextVersion,
                    blob.storeJson.length + blob.vectorsBin.length + blob.documentsJsonl.length);

            // 3. 写完后 GetObject 拉一次 store.json，验证 snapshotVersion 一致（防 3 文件撕裂）
            verifyOssSnapshotVersion(storeName, nextVersion);
        } catch (Exception e) {
            log.error("Failed to save store [{}] to OSS (in-memory data preserved): {}",
                    storeName, e.getMessage(), e);
        }
    }

    /**
     * 生成下一个 snapshotVersion：形如 {@code v_<timestamp>_<seq>}。
     * <p>同毫秒多次写通过 seq 区分。
     */
    private String nextSnapshotVersion(String current) {
        long now = System.currentTimeMillis();
        int seq = 1;
        if (current != null && current.startsWith("v_")) {
            String[] parts = current.substring(2).split("_");
            if (parts.length == 2) {
                try {
                    long ts = Long.parseLong(parts[0]);
                    int prevSeq = Integer.parseInt(parts[1]);
                    if (ts == now) {
                        seq = prevSeq + 1;
                    }
                } catch (NumberFormatException ignored) {
                    // 老格式无法解析,使用 now + seq=1
                }
            }
        }
        return "v_" + now + "_" + seq;
    }

    /**
     * 写完 OSS 后 GetObject 拉一次 store.json，验证 snapshotVersion 与预期一致。
     * <p>防 3 个 PutObject 中间挂掉造成的远端撕裂：只要 store.json 实际 version
     * 跟刚写的不一致,说明写入过程中丢了某个文件,下个 30s 周期会自动重写。
     */
    private void verifyOssSnapshotVersion(String storeName, String expectedVersion) {
        try {
            String key = storePrefix(storeName) + F_STORE_JSON;
            byte[] data = getBytes(key);
            VectorStoreDefinition def = objectMapper.readValue(data, VectorStoreDefinition.class);
            String actual = def.getSnapshotVersion();
            if (!expectedVersion.equals(actual)) {
                log.error("OSS write verify FAILED for store [{}]: expected snapshotVersion={}, actual={}. " +
                                "The next 30s flush cycle will retry.",
                        storeName, expectedVersion, actual);
            }
        } catch (Exception e) {
            log.warn("OSS write verify skipped for store [{}] due to error: {}",
                    storeName, e.getMessage());
        }
    }

    /**
     * 把 3 段字节流原子写入本地 mmap 缓存目录。
     * <p>采用 {@code .tmp} 目录 + 原子交换，保证崩溃时不会出现 3 文件新旧混搭的中间态。
     */
    private void writeLocalFiles(String storeName, SnapshotSerializer.Blob blob) {
        try {
            String basePath = properties.getStorage().getSnapshotFile().getBasePath();
            File parent = new File(basePath);
            if (!parent.exists() && !parent.mkdirs()) {
                log.warn("Failed to mkdir {}", parent.getAbsolutePath());
                return;
            }
            File storeDir = new File(parent, storeName);
            File tmpDir = SnapshotFileUtil.prepareTempDir(parent, storeName);
            try {
                writeBytes(new File(tmpDir, F_STORE_JSON), blob.storeJson);
                writeBytes(new File(tmpDir, F_VECTORS_BIN), blob.vectorsBin);
                writeBytes(new File(tmpDir, F_DOCUMENTS_JSONL), blob.documentsJsonl);
                SnapshotFileUtil.swapDirectoryAtomic(tmpDir, storeDir, storeName);
            } catch (IOException e) {
                log.warn("Failed to write local snapshot for store [{}]: {}", storeName, e.getMessage());
                SnapshotFileUtil.deleteRecursively(tmpDir);
            }
        } catch (Exception e) {
            log.warn("Failed to prepare local snapshot dir for store [{}]: {}", storeName, e.getMessage());
        }
    }

    private static void writeBytes(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private void putBytes(String key, byte[] data) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(data.length);
        oss.putObject(new PutObjectRequest(bucket, key,
                new ByteArrayInputStream(data), meta));
    }

    private void retryWithBackoff(String opName, ThrowingRunnable op) {
        int max = properties.getStorage().getOss().getRetryTimes();
        long backoff = properties.getStorage().getOss().getRetryBackoffMs();
        Exception last = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                op.run();
                return;
            } catch (Exception e) {
                last = e;
                log.warn("{} failed on attempt {}/{}: {}", opName, attempt, max, e.getMessage());
                if (attempt < max) {
                    try { Thread.sleep(backoff * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        throw new RuntimeException(opName + " failed after " + max + " attempts", last);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ----------------------------------------------------------------
    // loadStore
    // ----------------------------------------------------------------
    @Override
    public void loadStore(LocalVectorStore store) {
        if (store == null || store.getDefinition() == null) return;
        String storeName = store.getDefinition().getStoreName();
        try {
            String prefix = storePrefix(storeName);
            // 检查 OSS 上是否存在
            if (!oss.doesObjectExist(bucket, prefix + F_STORE_JSON)) {
                return;
            }
            byte[] storeJson = getBytes(prefix + F_STORE_JSON);
            byte[] vectorsBin = getBytes(prefix + F_VECTORS_BIN);
            byte[] docsJsonl = getBytes(prefix + F_DOCUMENTS_JSONL);

            SnapshotSerializer.deserialize(store, storeJson, vectorsBin, docsJsonl);
            log.info("Loaded store [{}] from OSS", storeName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load store [" + storeName + "] from OSS: " + e.getMessage(), e);
        }
    }

    private byte[] getBytes(String key) throws IOException {
        OSSObject obj = oss.getObject(new GetObjectRequest(bucket, key));
        try (var in = obj.getObjectContent()) {
            return in.readAllBytes();
        }
    }

    // ----------------------------------------------------------------
    // listStoreNames
    // ----------------------------------------------------------------
    @Override
    public List<String> listStoreNames() {
        try {
            ObjectListing listing = oss.listObjects(new ListObjectsRequest(bucket)
                    .withPrefix(keyPrefix)
                    .withDelimiter("/")
                    .withMaxKeys(1000));
            List<String> result = new ArrayList<>();
            for (String commonPrefix : listing.getCommonPrefixes()) {
                // commonPrefix 形如 "veclite/store1/"
                String trimmed = commonPrefix;
                if (trimmed.startsWith(keyPrefix)) {
                    trimmed = trimmed.substring(keyPrefix.length());
                }
                if (trimmed.endsWith("/")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                if (!trimmed.isEmpty() && !trimmed.contains("/")) {
                    result.add(trimmed);
                }
            }
            // OSS 翻页
            while (listing.isTruncated()) {
                listing = oss.listObjects(new ListObjectsRequest(bucket)
                        .withPrefix(keyPrefix)
                        .withDelimiter("/")
                        .withMarker(listing.getNextMarker())
                        .withMaxKeys(1000));
                for (String commonPrefix : listing.getCommonPrefixes()) {
                    String trimmed = commonPrefix;
                    if (trimmed.startsWith(keyPrefix)) {
                        trimmed = trimmed.substring(keyPrefix.length());
                    }
                    if (trimmed.endsWith("/")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1);
                    }
                    if (!trimmed.isEmpty() && !trimmed.contains("/")) {
                        result.add(trimmed);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to list stores from OSS: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ----------------------------------------------------------------
    // loadStoreDefinition
    // ----------------------------------------------------------------
    @Override
    public VectorStoreDefinition loadStoreDefinition(String storeName) {
        try {
            String key = storePrefix(storeName) + F_STORE_JSON;
            if (!oss.doesObjectExist(bucket, key)) return null;
            byte[] data = getBytes(key);
            return objectMapper.readValue(data, VectorStoreDefinition.class);
        } catch (Exception e) {
            log.error("Failed to load store definition [{}] from OSS: {}", storeName, e.getMessage());
            return null;
        }
    }

    // ----------------------------------------------------------------
    // deleteStore
    // ----------------------------------------------------------------
    @Override
    public void deleteStore(String storeName) {
        try {
            String prefix = storePrefix(storeName);
            ObjectListing listing = oss.listObjects(new ListObjectsRequest(bucket).withPrefix(prefix));
            List<String> keys = new ArrayList<>();
            for (OSSObjectSummary s : listing.getObjectSummaries()) {
                keys.add(s.getKey());
            }
            // 翻页
            while (listing.isTruncated()) {
                listing = oss.listObjects(new ListObjectsRequest(bucket)
                        .withPrefix(prefix)
                        .withMarker(listing.getNextMarker()));
                for (OSSObjectSummary s : listing.getObjectSummaries()) {
                    keys.add(s.getKey());
                }
            }
            if (!keys.isEmpty()) {
                // 分批删除（OSS 一次最多 1000 个 key）
                for (int i = 0; i < keys.size(); i += 1000) {
                    int end = Math.min(i + 1000, keys.size());
                    List<String> batch = keys.subList(i, end);
                    oss.deleteObjects(new DeleteObjectsRequest(bucket).withKeys(batch)
                            .withEncodingType("url"));
                }
            }
            // 同步删本地
            String basePath = properties.getStorage().getSnapshotFile().getBasePath();
            File dir = new File(basePath, storeName);
            SnapshotFileUtil.deleteRecursively(dir);
            log.info("Deleted store [{}] from OSS and local", storeName);
        } catch (Exception e) {
            log.error("Failed to delete store [{}] from OSS: {}", storeName, e.getMessage());
        }
    }

    /**
     * 健康检查：测试能否访问 OSS。
     */
    public boolean isHealthy() {
        try {
            return oss.doesBucketExist(bucket);
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------------------------------
    // 本地缓存工具（供 OssStartupLoader 用，实现"本地优先"启动策略）
    // ----------------------------------------------------------------

    /**
     * 检查本地 mmap 缓存目录是否包含完整的 store 快照（3 个文件齐全且非空）。
     * <p>被 {@link OssStartupLoader} 用于：启动时优先从本地恢复，避免无谓的 OSS 调用。
     */
    public boolean hasLocalSnapshot(String storeName) {
        String basePath = properties.getStorage().getSnapshotFile().getBasePath();
        File dir = new File(basePath, storeName);
        File sj = new File(dir, F_STORE_JSON);
        File vb = new File(dir, F_VECTORS_BIN);
        File dj = new File(dir, F_DOCUMENTS_JSONL);
        return sj.isFile() && sj.length() > 0
                && vb.isFile() && vb.length() > 0
                && dj.isFile() && dj.length() >= 0;  // documents.jsonl 可为空（空 store）
    }

    /**
     * 从本地 mmap 缓存目录加载 store 快照到内存。
     * <p>调用方需先确保 {@link #hasLocalSnapshot} 返回 true。
     *
     * @param store 已经创建好的内存 store 骨架
     */
    public void loadStoreFromLocal(LocalVectorStore store) {
        if (store == null || store.getDefinition() == null) return;
        String storeName = store.getDefinition().getStoreName();
        String basePath = properties.getStorage().getSnapshotFile().getBasePath();
        File dir = new File(basePath, storeName);
        File sj = new File(dir, F_STORE_JSON);
        File vb = new File(dir, F_VECTORS_BIN);
        File dj = new File(dir, F_DOCUMENTS_JSONL);
        try {
            byte[] storeJson = java.nio.file.Files.readAllBytes(sj.toPath());
            byte[] vectorsBin = java.nio.file.Files.readAllBytes(vb.toPath());
            byte[] docsJsonl = java.nio.file.Files.readAllBytes(dj.toPath());
            SnapshotSerializer.deserialize(store, storeJson, vectorsBin, docsJsonl);
            log.info("Loaded store [{}] from local mmap cache ({} bytes)",
                    storeName, storeJson.length + vectorsBin.length + docsJsonl.length);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load store [" + storeName + "] from local cache: "
                    + e.getMessage(), e);
        }
    }

    /**
     * 暴露本地缓存目录，供 OssStartupLoader 判断 / 调试。
     */
    public File getLocalStoreDir(String storeName) {
        String basePath = properties.getStorage().getSnapshotFile().getBasePath();
        return new File(basePath, storeName);
    }
}
