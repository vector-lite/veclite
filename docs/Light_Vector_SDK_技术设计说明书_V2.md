# Light Vector SDK 技术设计说明书 V2.0

> 面向 Spring Boot 内嵌场景的轻量级向量 SDK（目标：100 万以内向量）

## 1. 项目背景

当前业务统一使用 Elasticsearch 提供向量检索。随着业务增长，ES 向量检索
CPU 开销越来越高，小规模知识库也需要依赖 ES 集群。

本项目目标不是实现新的 Vector Database，而是提供一个 **轻量级 Java
SDK**，业务通过引入 Starter 即可拥有本地向量检索能力。

------------------------------------------------------------------------

# 2. 设计目标

-   支持百万以内向量管理
-   Spring Boot Starter 接入
-   Java17
-   多 Store 管理
-   Metadata Filter
-   TopK 查询
-   本地持久化
-   API 简单
-   后续可扩展 HNSW

不做： - 独立服务 - 数据库事务 - LSM - Compaction - 数据页管理

------------------------------------------------------------------------

# 3. 整体架构

    Business

       │
    Vector Starter
       │
    VectorService
       │
    StoreManager
       │
    VectorStore
       ├── Searcher
       ├── Storage
       ├── Quantizer
       └── Filter

------------------------------------------------------------------------

# 4. 核心模块

## StoreManager

负责：

-   创建 Store
-   删除 Store
-   加载 Store
-   生命周期管理

------------------------------------------------------------------------

## VectorStore

每个 Store 包含：

    StoreName
    Dimension
    VectorCount
    Searcher
    Storage
    Quantizer

------------------------------------------------------------------------

## Searcher

统一接口：

``` java
interface Searcher{
    List<SearchResult> search(SearchRequest request);
}
```

### V2 默认实现

FlatSearcher

特点：

-   100% Recall
-   实现简单
-   易维护

### V3 扩展

    Searcher
       │
     ├── FlatSearcher
     ├── HnswSearcher
     └── IvfSearcher

------------------------------------------------------------------------

## Storage

``` java
interface Storage{
    load();
    flush();
    put();
    delete();
}
```

默认：

LocalFileStorage

V3：

MmapStorage

------------------------------------------------------------------------

## Quantizer

    Quantizer
       │
     ├── None
     ├── SQ8
     └── PQ(V3)

默认开启 SQ8。

------------------------------------------------------------------------

## Metadata Filter

V2：

Map Predicate Filter

V3：

RoaringBitmap Filter

------------------------------------------------------------------------

# 5. 数据模型

## VectorEntity

``` text
id
vector
metadata
```

## SearchRequest

``` text
queryVector
topK
filter
```

## SearchResult

``` text
id
score
metadata
```

------------------------------------------------------------------------

# 6. 多向量库设计

支持：

``` yaml
vector:
  stores:
    knowledge:
      dimension: 1024

    faq:
      dimension: 768

    product:
      dimension: 512
```

业务启动时自动创建。

------------------------------------------------------------------------

# 7. Starter 配置

``` yaml
vector:

  enabled: true

  persistence: true

  quantize: sq8

  searcher: flat

  storage: file

  stores:

    knowledge:
      dimension:1024
      topK:20

    faq:
      dimension:768
```

------------------------------------------------------------------------

# 8. API

``` java
createStore()

dropStore()

upsert()

batchUpsert()

delete()

search()
```

------------------------------------------------------------------------

# 9. 查询流程

    SearchRequest
          │
    Metadata Filter
          │
    Searcher
          │
    TopK Heap
          │
    SearchResult

------------------------------------------------------------------------

# 10. 持久化

目录：

    store/

     vectors.bin

     metadata.json

     config.json

启动：

读取文件 -\> 恢复内存。

关闭：

flush 到本地。

V2 不引入数据库级 WAL。

------------------------------------------------------------------------

# 11. SQ8

默认开启。

优势：

-   内存降低约 4 倍
-   百万向量内存可控制在约 500MB
-   对 Recall 影响较小

------------------------------------------------------------------------

# 12. 为什么 V2 不默认采用 HNSW

原因：

-   Flat Search 为 Exact Search，100% Recall。
-   HNSW 为 ANN，存在漏召回。
-   ES 中调大 num_candidates 才能找回真正 Top1，本质就是 ANN 特性。
-   SDK 第一版优先保证结果正确性与易维护。

因此：

默认：

Flat + SQ8。

预留：

Searcher SPI。

------------------------------------------------------------------------

# 13. V3 扩展

Searcher：

    FlatSearcher
    HnswSearcher
    IvfSearcher

Storage：

    LocalFileStorage
    MmapStorage

Quantizer：

    None
    SQ8
    PQ

Filter：

    Predicate
    Bitmap

全部通过接口扩展，无需修改业务代码。

------------------------------------------------------------------------

# 14. 性能目标

  规模        推荐
  ----------- ---------------------------------------
  10万以内    Flat
  10\~50万    Flat+SQ8
  50\~100万   Flat+SQ8+LocalFile
  100万以上   建议继续使用 ES/Milvus 或启用 V3 HNSW

------------------------------------------------------------------------

# 15. 与 ES 对比

  能力         ES         Vector SDK
  ------------ ---------- ------------
  部署         独立集群   Jar
  维护         高         低
  资源占用     高         低
  100万以内    适合       非常适合
  查询准确率   ANN        Exact
  运维         需要       无需

------------------------------------------------------------------------

# 16. 开发路线

## V2（首版）

-   Starter
-   多 Store
-   Flat Search
-   SQ8
-   LocalFile
-   Metadata Filter
-   配置化管理

预计核心代码约 2500\~3500 行。

## V3

-   HNSW
-   mmap
-   Bitmap
-   PQ
-   并行搜索

全部作为插件实现。
