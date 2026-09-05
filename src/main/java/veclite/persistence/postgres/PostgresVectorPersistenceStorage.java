package veclite.persistence.postgres;

import veclite.config.VectorLiteProperties;
import veclite.persistence.AbstractDocumentPersistence;
import veclite.persistence.VectorDocumentRepository;

/**
 * {@link veclite.persistence.DocumentBackedPersistence} 的 PostgreSQL 实现。
 * <p>
 * 与 {@code MongoVectorPersistenceStorage} 同构：文档（text/metadata/向量）写透落库 RPO=0，
 * 启动时按元数据集合发现存量库并游标重建。编排逻辑由 {@link AbstractDocumentPersistence} 承担，
 * 本类只负责绑定 PostgreSQL 仓储。
 */
public class PostgresVectorPersistenceStorage extends AbstractDocumentPersistence {

    public PostgresVectorPersistenceStorage(VectorDocumentRepository repository, VectorLiteProperties properties) {
        super(repository, properties);
    }
}
