package veclite.embedding;

import java.util.Objects;

/**
 * Embedding 模型引用：名称 + 版本二元组。
 * <p>
 * 同名模型可以有多个版本（不同版本产生不同 embedding 结果），
 * 因此数据源的唯一标识、默认模型标记均以（名称， 版本）为准；
 * {@code version} 为 null 表示"该名称下的主版本"或"未指定"。
 */
public record EmbeddingModelRef(String name, String version) {

    public EmbeddingModelRef {
        Objects.requireNonNull(name, "name must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof EmbeddingModelRef other)) {
            return false;
        }
        return name.equals(other.name) && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, version);
    }

    @Override
    public String toString() {
        return version == null ? name : name + ":" + version;
    }
}
