package veclite.persistence.mongo;

import veclite.config.VectorLiteProperties;
import veclite.model.StorageType;
import veclite.persistence.AbstractDocumentPersistence;
import veclite.persistence.VectorDocumentRepository;

/**
 * {@link veclite.persistence.DocumentBackedPersistence} 的 MongoDB 实现（v2.5 单一真相源持久化）。
 * <p>
 * 编排逻辑（写透、整库装载、整库对账）全部由 {@link AbstractDocumentPersistence} 承担，
 * 本类只负责绑定 MongoDB 仓储与数据位置标记 {@link StorageType#MONGODB}。
 */
public class MongoVectorPersistenceStorage extends AbstractDocumentPersistence {

    public MongoVectorPersistenceStorage(VectorDocumentRepository repository, VectorLiteProperties properties) {
        super(repository, StorageType.MONGODB, properties);
    }
}
