package veclite.embedding;

import veclite.config.VectorLiteProperties;

import java.util.List;

/**
 * Embedding 模型配置的持久化端口。
 * <p>
 * 实现负责"托管模型配置"的存取（当前为 MongoDB 的 {@code veclite_embedding_model} 集合），
 * 唯一键为（模型名称， 模型版本）——同名模型的不同版本是不同数据源；
 * {@code application.yml} 中的静态配置不经过本端口，始终作为基线存在。
 */
public interface EmbeddingModelStore {

    /** 加载全部托管模型配置 */
    List<VectorLiteProperties.ModelConfig> loadAll();

    /** 保存（upsert）一个托管模型配置，以（name, version）为主键 */
    void save(VectorLiteProperties.ModelConfig config);

    /** 删除指定托管模型配置，返回是否实际删除 */
    boolean delete(String name, String version);

    /**
     * 持久化"默认模型"标记（精确到版本；ref 为 null 表示清除标记）。
     * 兼容旧格式：仅含名称的旧标记按"该名称主版本"处理。
     */
    void saveDefault(EmbeddingModelRef ref);

    /** 读取持久化的"默认模型"标记，未设置时返回 null */
    EmbeddingModelRef loadDefault();
}
