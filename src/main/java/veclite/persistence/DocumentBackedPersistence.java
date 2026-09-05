package veclite.persistence;

import veclite.engine.LocalVectorStore;
import veclite.model.StoreSyncResult;
import veclite.model.VectorDocument;
import veclite.api.VectorStoreMetadata;

import java.util.List;

/**
 * 文档型持久化编排端口（MongoDB 等单一真相源方案的写透接口）。
 * <p>
 * 在 {@link VectorPersistenceStorage} 的"整库 save/load/delete"语义之上，
 * 增加文档级写透、Store 元数据维护与增量同步能力。真相源中持有全量文档，
 * 写入路径先提交真相源再更新内存，内存永远是真相源的一个可重建投影。
 * <p>
 * 引擎侧通过 {@code instanceof DocumentBackedPersistence} 探测，
 * 快照/Noop 等非文档型实现不受影响。
 */
public interface DocumentBackedPersistence extends VectorPersistenceStorage {

    /**
     * 将文档批量写透到真相源（含向量本体，FLOAT32 原始格式）。
     * 调用方保证文档已完成 embedding（vector 非空）。
     */
    void upsertDocuments(LocalVectorStore store, List<VectorDocument> documents);

    /** 从真相源软删除指定文档（tombstone 保留，供其他节点增量感知；ID 不存在时静默跳过） */
    void deleteDocuments(String storeName, List<String> documentIds);

    /** 保存/更新 Store 元数据（定义配置、persistenceMode、SQ8 冻结参数、activeCount、同步水位） */
    void saveStoreMetadata(LocalVectorStore store);

    /**
     * 增量同步：按元数据水位拉取真相源变更并应用到内存，返回应用统计与推进后的水位。
     * 多节点部署下定时收敛内存投影的轻量通道；水位缺失时跳过并提示先整库装载建立基线。
     */
    StoreSyncResult incrementalSync(LocalVectorStore store);

    /** 发现全部已注册 Store 的元数据（启动双发现用） */
    List<VectorStoreMetadata> listStoreMetadata();
}
