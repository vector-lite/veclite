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
        private OffHeapConfig offHeap = new OffHeapConfig();
        private PayloadConfig payload = new PayloadConfig();
        private OssConfig oss = new OssConfig();

        public StorageType getType() {
            return type;
        }

        public void setType(StorageType type) {
            this.type = type;
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

        public OssConfig getOss() {
            return oss;
        }

        public void setOss(OssConfig oss) {
            this.oss = oss;
        }
    }

    public static class OssConfig {
        private String endpoint;
        private String accessKeyId;
        private String accessKeySecret;
        private String bucket;
        private String keyPrefix = "veclite/";
        private int retryTimes = 3;
        private long retryBackoffMs = 500L;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 30000;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

        public String getAccessKeySecret() { return accessKeySecret; }
        public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }

        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }

        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

        public int getRetryTimes() { return retryTimes; }
        public void setRetryTimes(int retryTimes) { this.retryTimes = retryTimes; }

        public long getRetryBackoffMs() { return retryBackoffMs; }
        public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    private ConsistencyConfig consistency = new ConsistencyConfig();
    private NodeConfig node = new NodeConfig();

    public NodeConfig getNode() {
        return node;
    }

    public void setNode(NodeConfig node) {
        this.node = node;
    }

    public static class NodeConfig {
        private String role = "master";
        private String nodeId = "";

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }

        public boolean isMaster() { return "master".equalsIgnoreCase(role); }
        public boolean isReplica() { return "replica".equalsIgnoreCase(role); }
    }

    public ConsistencyConfig getConsistency() {
        return consistency;
    }

    public void setConsistency(ConsistencyConfig consistency) {
        this.consistency = consistency;
    }

    public static class ConsistencyConfig {
        private boolean strict = false;

        public boolean isStrict() {
            return strict;
        }

        public void setStrict(boolean strict) {
            this.strict = strict;
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
        private int dimension = 0;
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
}
