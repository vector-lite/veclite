package veclite.persistence.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.ListIndexesIterable;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.config.VectorLiteProperties;


import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 共享 MongoClient 语义回归：注入外部客户端的仓储 / 模型存储在 close() 时
 * 不得关闭共享连接——生命周期由自动配置的 vecliteMongoClient Bean 统一持有，
 * 否则两个组件各自 MongoClients.create 会形成启动即双连接池。
 */
class MongoSharedClientTest {

    @Test
    @DisplayName("共享客户端构造的向量仓储 close() 不关闭外部 MongoClient")
    @SuppressWarnings("unchecked")
    void repositoryCloseDoesNotCloseSharedClient() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> meta = mock(MongoCollection.class);
        when(client.getDatabase("veclite")).thenReturn(database);
        when(database.getCollection("veclite_store_meta")).thenReturn(meta);

        MongoVectorDocumentRepository repository =
                new MongoVectorDocumentRepository(client, new VectorLiteProperties());
        repository.close();

        verify(client, never()).close();
    }

    @Test
    @DisplayName("共享客户端构造的模型存储 close() 不关闭外部 MongoClient")
    @SuppressWarnings("unchecked")
    void embeddingStoreCloseDoesNotCloseSharedClient() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("veclite")).thenReturn(database);
        when(database.getCollection("veclite_embedding_model")).thenReturn(collection);
        ListIndexesIterable<Document> indexes = mock(ListIndexesIterable.class);
        doReturn(mock(MongoCursor.class)).when(indexes).iterator();
        doReturn(indexes).when(collection).listIndexes();

        MongoEmbeddingModelStore store =
                new MongoEmbeddingModelStore(client, new VectorLiteProperties());
        store.close();

        verify(client, never()).close();
    }
}
