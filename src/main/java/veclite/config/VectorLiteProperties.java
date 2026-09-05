package veclite.config;

import veclite.model.QuantizationType;
import veclite.model.StorageType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "veclite", ignoreInvalidFields = true)
public class VectorLiteProperties {
    private boolean enabled = true;
    private WebConfig web = new WebConfig();
    private StorageConfig storage = new StorageConfig();
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
        /** 数据库是运行时唯一支持的持久化后端；默认使用 PostgreSQL。 */
        private StorageType type = StorageType.POSTGRES;
        private SnapshotFileConfig snapshotFile = new SnapshotFileConfig();
        private OffHeapConfig offHeap = new OffHeapConfig();
        private PayloadConfig payload = new PayloadConfig();
        private MongoConfig mongodb = new MongoConfig();
        private PostgresConfig postgres = new PostgresConfig();
        private SyncConfig sync = new SyncConfig();

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

        public MongoConfig getMongodb() {
            return mongodb;
        }

        public void setMongodb(MongoConfig mongodb) {
            this.mongodb = mongodb;
        }

        public PostgresConfig getPostgres() {
            return postgres;
        }

        public void setPostgres(PostgresConfig postgres) {
            this.postgres = postgres;
        }

        public SyncConfig getSync() {
            return sync;
        }

        public void setSync(SyncConfig sync) {
            this.sync = sync;
        }
    }

    /**
     * 增量同步调度（文档型后端 MONGODB/POSTGRES 专用）：多节点部署下按水位从真相源
     * 收敛本节点内存投影，替代旧版"定时全量重载"的轻量方案。
     */
    public static class SyncConfig {
        /** 是否启用定时增量同步调度器（默认关闭，多节点部署显式开启） */
        private boolean enabled = false;
        /** 调度间隔（秒），按固定延迟执行 */
        private int intervalSeconds = 30;
        /** 软删除 tombstone 物理保留天数，超过后被清理；<=0 表示不清理 */
        private int retentionDays = 7;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }

    /** MongoDB 单一真相源持久化（StorageType.MONGODB）的连接与集合配置 */
    public static class MongoConfig {
        private String uri = "mongodb://localhost:27017";
        private String database = "veclite";
        private String metaCollection = "veclite_store_meta";
        private String embeddingModelCollection = "veclite_embedding_model";
        private int scanBatchSize = 1000;

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getMetaCollection() {
            return metaCollection;
        }

        public void setMetaCollection(String metaCollection) {
            this.metaCollection = metaCollection;
        }

        public String getEmbeddingModelCollection() {
            return embeddingModelCollection;
        }

        public void setEmbeddingModelCollection(String embeddingModelCollection) {
            this.embeddingModelCollection = embeddingModelCollection;
        }

        public int getScanBatchSize() {
            return scanBatchSize;
        }

        public void setScanBatchSize(int scanBatchSize) {
            this.scanBatchSize = scanBatchSize;
        }
    }

    /** PostgreSQL 单一真相源持久化（StorageType.POSTGRES）的连接与表配置 */
    public static class PostgresConfig {
        private String jdbcUrl = "jdbc:postgresql://localhost:5432/veclite";
        private String username = "postgres";
        private String password = "";
        private String metaTable = "veclite_store_meta";
        private String embeddingModelTable = "veclite_embedding_model";

        /** 游标扫描批大小：对应 JDBC 的 fetchSize，控制启动装载时的内存占用 */
        private int fetchSize = 1000;

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getMetaTable() {
            return metaTable;
        }

        public void setMetaTable(String metaTable) {
            this.metaTable = metaTable;
        }

        public String getEmbeddingModelTable() {
            return embeddingModelTable;
        }

        public void setEmbeddingModelTable(String embeddingModelTable) {
            this.embeddingModelTable = embeddingModelTable;
        }

        public int getFetchSize() {
            return fetchSize;
        }

        public void setFetchSize(int fetchSize) {
            this.fetchSize = fetchSize;
        }
    }

    /** 落盘前是否强制校验 vec / payload / idIndex 三者 size 一致（Fail-Fast 不变量断言） */
    private ConsistencyConfig consistency = new ConsistencyConfig();

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

    /**
     * 本地文件快照目录配置。SNAPSHOT_FILE 持久化已退出生产路径（数据库为唯一持久化后端），
     * 仅 PayloadMode.MMAP 下 payload 落盘文件仍使用该 basePath。
     */
    public static class SnapshotFileConfig {
        private String basePath = "./data/vector-lite";

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
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
        private int batchSize = 1;
        private boolean isDefault;

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

        public boolean isDefault() { return isDefault; }
        public void setDefault(boolean value) { this.isDefault = value; }
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
