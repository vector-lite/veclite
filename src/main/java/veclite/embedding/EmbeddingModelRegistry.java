package veclite.embedding;


import veclite.config.VectorLiteProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.Set;

/**
 * Embedding 模型配置注册中心：yml 静态配置与托管配置的合并视图。
 * <p>
 * 模型以（名称， 版本）为唯一标识——同名模型的不同版本是不同数据源（产生不同 embedding 结果）。
 * 全部配置经由 {@link EmbeddingModelStore} 持久化到数据库（MongoDB 模式下落库），
 * 增删改即时生效，无需重启，也不再依赖 application.yml。
 * <ul>
 *   <li>{@link #save}：新增或覆盖（按名称+版本 upsert）；</li>
 *   <li>{@link #delete}：删除指定（名称， 版本）的配置；</li>
 *   <li>{@link #saveDefault}：设置默认模型（精确到版本）；</li>
 *   <li>{@link #primaryVersion}：同一名称下多个版本时的"主版本"——默认标记指定的版本优先，否则取最先创建的版本。</li>
 * </ul>
 */
public class EmbeddingModelRegistry {

    /** 支持的 provider 协议，与 {@link EmbeddingHttpAdapter#forProvider} 保持一致 */
    public static final Set<String> SUPPORTED_PROVIDERS = Set.of("http", "openai", "ollama", "ollama-embed");

    private final EmbeddingModelStore store;
    /** 托管配置：key 为（name + '\u001F' + version）复合键；LinkedHashMap 保证主版本按插入序解析 */
    private final Map<String, VectorLiteProperties.ModelConfig> managed =
            Collections.synchronizedMap(new LinkedHashMap<>());
    /** 托管侧设置的默认模型；为 null 时回退到 yml 的 default-model */
    private volatile EmbeddingModelRef managedDefault;

    public EmbeddingModelRegistry(EmbeddingModelStore store) {
        this.store = store;
        reload();
    }

    /** 启动时从持久化端口装载托管配置；装载失败不阻断启动，退化为纯 yml 配置 */
    public final void reload() {
        managed.clear();
        managedDefault = null;
        if (store == null) {
            return;
        }
        try {
            for (VectorLiteProperties.ModelConfig config : store.loadAll()) {
                if (isValid(config)) {
                    managed.put(key(config.getName(), config.getVersion()), config);
                }
            }
            managedDefault = store.loadDefault();
        } catch (Exception ignored) {
            // 持久化端口不可用时保持空覆盖层，yml 配置仍然生效
        }
    }

    /** 全部生效配置列表（按创建顺序）。 */
    public List<VectorLiteProperties.ModelConfig> effectiveList() {
        synchronized (managed) {
            return new ArrayList<>(managed.values());
        }
    }

    /**
     * 查找模型配置；{@code version} 为 null 时返回该名称下的主版本。
     */
    public VectorLiteProperties.ModelConfig find(String name, String version) {
        if (name == null) {
            return null;
        }
        String target = version != null ? version : primaryVersion(name);
        if (target == null) {
            return null;
        }
        VectorLiteProperties.ModelConfig config = effectiveMap().get(key(name, target));
        return config != null ? copyOf(config) : null;
    }

    /** 该名称下是否存在任一版本的配置 */
    public boolean hasName(String name) {
        if (name == null) {
            return false;
        }
        for (VectorLiteProperties.ModelConfig config : effectiveList()) {
            if (name.equals(config.getName())) {
                return true;
            }
        }
        return false;
    }

    public boolean isManaged(String name, String version) {
        return name != null && managed.containsKey(key(name, version));
    }

    /**
     * 同一名称下的主版本：默认标记指定的版本优先，否则取合并视图中该名称的第一个版本。
     */
    public String primaryVersion(String name) {
        EmbeddingModelRef ref = managedDefault;
        if (ref != null && ref.name().equals(name) && ref.version() != null
                && effectiveMap().containsKey(key(name, ref.version()))) {
            return ref.version();
        }
        for (VectorLiteProperties.ModelConfig config : effectiveList()) {
            if (name.equals(config.getName())) {
                return config.getVersion();
            }
        }
        return null;
    }

    /** 当前默认模型（精确到版本），未设置时返回 null。 */
    public EmbeddingModelRef defaultRef() {
        if (managedDefault != null) {
            VectorLiteProperties.ModelConfig config = find(managedDefault.name(), managedDefault.version());
            if (config != null) {
                return new EmbeddingModelRef(config.getName(), config.getVersion());
            }
        }
        return null;
    }

    /**
     * 新增或更新模型配置（按名称+版本 upsert），立即生效并持久化。
     *
     * @throws IllegalArgumentException 校验失败时抛出
     */
    public synchronized void save(VectorLiteProperties.ModelConfig config) {
        validate(config);
        VectorLiteProperties.ModelConfig toPersist = copyOf(config);
        if (store != null) {
            store.save(toPersist);
        }
        managed.put(key(toPersist.getName(), toPersist.getVersion()), toPersist);
        // 尚无默认模型时，首个入库的数据源自动成为默认
        if (managedDefault == null && managed.size() == 1) {
            EmbeddingModelRef ref = new EmbeddingModelRef(toPersist.getName(), toPersist.getVersion());
            if (store != null) {
                store.saveDefault(ref);
            }
            managedDefault = ref;
        }
    }

    /**
     * 删除托管模型配置（按名称+版本），立即生效并持久化。
     *
     * @throws IllegalArgumentException 配置不存在或属于 yml 内置配置（只能覆盖不能删除）时抛出
     */
    public synchronized void delete(String name, String version) {
        String display = version == null ? name : name + ":" + version;
        if (name == null || find(name, version) == null) {
            throw new IllegalArgumentException("Embedding model [" + display + "] does not exist");
        }
        if (!isManaged(name, version)) {
            throw new IllegalArgumentException(
                    "Embedding model [" + display + "] is defined in application.yml and cannot be deleted. "
                            + "Override it via save if you want to change it.");
        }
        if (store != null) {
            store.delete(name, version);
        }
        managed.remove(key(name, version));
        if (managedDefault != null && managedDefault.name().equals(name)
                && Objects.equals(managedDefault.version(), version)) {
            if (store != null) {
                store.saveDefault(null);
            }
            managedDefault = null;
        }
    }

    /**
     * 置为默认模型（精确到版本）：目标必须是生效配置；yml 内置配置会先转为托管再标记，标记随配置持久化。
     */
    public synchronized void saveDefault(String name, String version) {
        VectorLiteProperties.ModelConfig config = find(name, version);
        String display = version == null ? name : name + ":" + version;
        if (config == null) {
            throw new IllegalArgumentException("Embedding model [" + display + "] does not exist");
        }
        if (!isManaged(config.getName(), config.getVersion())) {
            save(copyOf(config));
        }
        EmbeddingModelRef ref = new EmbeddingModelRef(config.getName(), config.getVersion());
        if (store != null) {
            store.saveDefault(ref);
        }
        managedDefault = ref;
    }

    private void validate(VectorLiteProperties.ModelConfig config) {
        if (config == null || config.getName() == null || config.getName().isBlank()) {
            throw new IllegalArgumentException("Embedding model name is required");
        }
        if (config.getUrl() == null || !config.getUrl().trim().startsWith("http")) {
            throw new IllegalArgumentException("Embedding model [" + config.getName() + "] url must be an http(s) endpoint");
        }
        String provider = config.getProvider() == null || config.getProvider().isBlank() ? "http" : config.getProvider().trim().toLowerCase();
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException(
                    "Unsupported embedding provider [" + provider + "]. Supported: " + SUPPORTED_PROVIDERS);
        }
        if (config.getTimeoutMillis() <= 0) {
            config.setTimeoutMillis(3000);
        }
        if (config.getBatchSize() <= 0) {
            config.setBatchSize(1);
        }
        if (config.getVersion() == null || config.getVersion().isBlank()) {
            config.setVersion("1");
        }
        config.setName(config.getName().trim());
        config.setProvider(provider);
        config.setUrl(config.getUrl().trim());
    }

    private boolean isValid(VectorLiteProperties.ModelConfig config) {
        return config != null && config.getName() != null && !config.getName().isBlank();
    }

    private Map<String, VectorLiteProperties.ModelConfig> effectiveMap() {
        Map<String, VectorLiteProperties.ModelConfig> merged = new LinkedHashMap<>();
        for (VectorLiteProperties.ModelConfig config : effectiveList()) {
            merged.put(key(config.getName(), config.getVersion()), config);
        }
        return merged;
    }

    private String key(String name, String version) {
        return name + "\u001F" + (version == null ? "" : version);
    }

    private VectorLiteProperties.ModelConfig copyOf(VectorLiteProperties.ModelConfig source) {
        VectorLiteProperties.ModelConfig copy = new VectorLiteProperties.ModelConfig();
        copy.setName(source.getName());
        copy.setVersion(source.getVersion());
        copy.setProvider(source.getProvider());
        copy.setUrl(source.getUrl());
        copy.setApiKey(source.getApiKey());
        copy.setDimension(source.getDimension());
        copy.setTimeoutMillis(source.getTimeoutMillis());
        copy.setBatchSize(source.getBatchSize());
        return copy;
    }
}
