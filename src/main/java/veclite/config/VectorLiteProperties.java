package veclite.config;

import veclite.model.QuantizationType;
import veclite.model.StorageType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "veclite")
public class VectorLiteProperties {
    private boolean enabled = true;
    private WebConfig web = new WebConfig();
    private StorageConfig storage = new StorageConfig();
    private EmbeddingConfig embedding = new EmbeddingConfig();
    private ConsistencyConfig consistency = new ConsistencyConfig();
    private Map<String, StoreConfig> stores = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public WebConfig getWeb() {
        return web;
    }

    public void setWeb(WebConfig web) {
        this.web = web;
    }

    public StorageConfig getStorage() {
        return storage;
    }

    public void setStorage(StorageConfig storage) {
        this.storage = storage;
    }

    public EmbeddingConfig getEmbedding() {
        return embedding;
    }

    public void setEmbedding(EmbeddingConfig embedding) {
        this.embedding = embedding;
    }

    public ConsistencyConfig getConsistency() {
        return consistency;
    }

    public void setConsistency(ConsistencyConfig consistency) {
        this.consistency = consistency;
    }

    public Map<String, StoreConfig> getStores() {
        return stores;
    }

    public void setStores(Map<String, StoreConfig> stores) {
        this.stores = stores;
    }

    public static class WebConfig {
        private boolean enabled = false;
        private String basePath = "/veclite/api/v1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }

    private SearcherConfig searcher = new SearcherConfig();

    public SearcherConfig getSearcher() {
        return searcher;
    }

    public void setSearcher(SearcherConfig searcher) {
        this.searcher = searcher;
    }

    public static class SearcherConfig {
        private ParallelConfig parallel = new ParallelConfig();
        private PrecomputationConfig precomputation = new PrecomputationConfig();

        public ParallelConfig getParallel() {
            return parallel;
        }

        public void setParallel(ParallelConfig parallel) {
            this.parallel = parallel;
        }

        public PrecomputationConfig getPrecomputation() {
            return precomputation;
        }

        public void setPrecomputation(PrecomputationConfig precomputation) {
            this.precomputation = precomputation;
        }
    }

    public static class PrecomputationConfig {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ParallelConfig {
        private boolean enabled = true;
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        private int minVectorCount = 10000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public int getMinVectorCount() {
            return minVectorCount;
        }

        public void setMinVectorCount(int minVectorCount) {
            this.minVectorCount = minVectorCount;
        }
    }

    public static class StorageConfig {
        private StorageType type = StorageType.SNAPSHOT_FILE;
        private SnapshotFileConfig snapshotFile = new SnapshotFileConfig();
        private OssConfig oss = new OssConfig();
        private OffHeapConfig offHeap = new OffHeapConfig();
        private PayloadConfig payload = new PayloadConfig();

        public StorageType getType() {
            return type;
        }

        public void setType(StorageType type) {
            this.type = type;
        }

        public OssConfig getOss() {
            return oss;
        }

        public void setOss(OssConfig oss) {
            this.oss = oss;
        }

        public SnapshotFileConfig getSnapshotFile() {
            return snapshotFile;
        }

        public void setSnapshotFile(SnapshotFileConfig snapshotFile) {
            this.snapshotFile = snapshotFile;
        }

        public OffHeapConfig getOffHeap() {
            return offHeap;
        }

        public void setOffHeap(OffHeapConfig offHeap) {
            this.offHeap = offHeap;
        }

        public PayloadConfig getPayload() {
            return payload;
        }

        public void setPayload(PayloadConfig payload) {
            this.payload = payload;
        }
    }

    public static class PayloadConfig {
        private veclite.model.PayloadMode mode = veclite.model.PayloadMode.MEMORY;

        public veclite.model.PayloadMode getMode() {
            return mode;
        }

        public void setMode(veclite.model.PayloadMode mode) {
            this.mode = mode;
        }
    }

    public static class OffHeapConfig {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class SnapshotFileConfig {
        private String basePath = "./data/vector-lite";
        private int flushIntervalSeconds = 30;
        private boolean flushOnShutdown = true;

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }

        public int getFlushIntervalSeconds() {
            return flushIntervalSeconds;
        }

        public void setFlushIntervalSeconds(int flushIntervalSeconds) {
            this.flushIntervalSeconds = flushIntervalSeconds;
        }

        public boolean isFlushOnShutdown() {
            return flushOnShutdown;
        }

        public void setFlushOnShutdown(boolean flushOnShutdown) {
            this.flushOnShutdown = flushOnShutdown;
        }
    }

    public static class EmbeddingConfig {
        private String defaultModel;
        private Map<String, ModelConfig> models = new HashMap<>();

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public Map<String, ModelConfig> getModels() {
            return models;
        }

        public void setModels(Map<String, ModelConfig> models) {
            this.models = models;
        }
    }

    public static class ModelConfig {
        private String name;
        private String version = "1";
        private String provider = "http";
        private String url;
        private String apiKey;
        private int dimension;            // 0 表示使用模型默认维度
        private int timeoutMillis = 1000;
        private int batchSize = 8;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class StoreConfig {
        private int dimension = 512;
        private String metric = "COSINE";
        private int maxCapacity = 100000;
        private String embeddingModel;
        private QuantizationType quantization = QuantizationType.NONE;
        private List<String> indexedMetadataFields = new ArrayList<>();

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public String getMetric() {
            return metric;
        }

        public void setMetric(String metric) {
            this.metric = metric;
        }

        public int getMaxCapacity() {
            return maxCapacity;
        }

        public void setMaxCapacity(int maxCapacity) {
            this.maxCapacity = maxCapacity;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public QuantizationType getQuantization() {
            return quantization;
        }

        public void setQuantization(QuantizationType quantization) {
            this.quantization = quantization;
        }

        public List<String> getIndexedMetadataFields() {
            return indexedMetadataFields;
        }

        public void setIndexedMetadataFields(List<String> indexedMetadataFields) {
            this.indexedMetadataFields = indexedMetadataFields;
        }
    }

    /**
     * 阿里云 OSS 持久化配置。
     * <p>所有敏感字段（AK/SK）应通过环境变量注入：
     * <ul>
     *   <li>ALIYUN_OSS_ACCESS_KEY_ID</li>
     *   <li>ALIYUN_OSS_ACCESS_KEY_SECRET</li>
     *   <li>ALIYUN_OSS_ENDPOINT</li>
     * </ul>
     */
    public static class OssConfig {
        /** OSS endpoint，例如 oss-cn-beijing.aliyuncs.com */
        private String endpoint;
        /** Bucket 名 */
        private String bucket;
        /** 对象 key 前缀，例如 veclite/ */
        private String keyPrefix = "veclite/";
        /** 写失败重试次数 */
        private int retryTimes = 3;
        /** 重试退避（毫秒） */
        private int retryBackoffMs = 500;
        /** 连接超时（毫秒） */
        private int connectTimeoutMs = 5000;
        /** 读超时（毫秒） */
        private int readTimeoutMs = 30000;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }

        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

        public int getRetryTimes() { return retryTimes; }
        public void setRetryTimes(int retryTimes) { this.retryTimes = retryTimes; }

        public int getRetryBackoffMs() { return retryBackoffMs; }
        public void setRetryBackoffMs(int retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    /**
     * 内部一致性配置（v2.4 § 4.4）。
     */
    public static class ConsistencyConfig {
        /**
         * 严格模式：assertConsistency 失败时直接抛 {@link veclite.engine.ConsistencyException}，
         * 阻止 saveStore 落盘。生产建议 {@code true}。
         */
        private boolean strict = true;

        public boolean isStrict() {
            return strict;
        }

        public void setStrict(boolean strict) {
            this.strict = strict;
        }
    }
}
