# VecLite 检索时序图

> 三张图覆盖三种检索方式的**跨层调用顺序**：向量检索、文本检索、混合检索。
> 重点说明：**文本检索 = "向量化 + 向量检索"两步走**，`hybridSearch` 当前是**文本优先 fallback**。

---

## 图 1：向量检索（searchByVector）

```mermaid
sequenceDiagram
    autonumber
    participant U as 调用方
    participant C as VectorLiteDebugController
    participant EC as VectorEngineClientImpl
    participant LE as LocalVectorEngine
    participant LS as LocalVectorStore
    participant PS as PayloadStorage
    participant VB as VectorBuffer
    participant MFI as MetadataFilterIndex
    participant VM as VectorMath

    U->>C: POST /stores/{name}/search/vector<br/>{queryVector, topK, filter?, minScore?}
    C->>EC: searchByVector(request)
    EC->>LE: getStore(storeName)
    LE-->>EC: LocalVectorStore
    EC->>LS: search(request)
    
    LS->>LS: 维度校验<br/>(queryVector.length == dimension)
    
    alt filter 不为空
        LS->>MFI: evaluate(filter)
        MFI-->>LS: BitSet matchingBitSet
    end
    
    LS->>LS: 读取 calibration snapshot
    LS->>LS: 计算 queryNormSq / queryNormInv<br/>(循环外预计算, 0 临时分配)
    
    alt 向量数 ≥ 并行阈值
        LS->>LS: searchParallel()
        Note over LS: 分段提交, 全局 Top-K 堆
    else 单线程
        LS->>LS: doSearch() 顺序扫描
    end
    
    loop 对每个候选 offset
        LS->>VB: calculateScoreZeroCopy(...)
        alt Float32 模式
            VB->>VM: calculate(metric, query, buf, offset)
        else SQ8 模式
            VB->>VM: SQ8Quantizer.calculate*<br/>(min/scale 反量化)
        end
        VM-->>VB: score
        VB-->>LS: score
        
        alt score 通过 minScore 阈值
            LS->>LS: offerCandidate(heap, topK)
            Note over LS: 堆未满 → 入堆<br/>堆已满 → 仅当优于堆顶时替换
        end
    end
    
    LS->>LS: pollAll(heap) → candidates
    LS->>PS: 批量查 payload(text, metadata)
    PS-->>LS: List<Payload>
    LS->>LS: buildResults(candidates, payload)
    LS-->>EC: List<VectorSearchResult>
    EC-->>C: List<VectorSearchResult>
    C-->>U: 200 OK + JSON
```

### 关键观察

- **零外部 HTTP 调用**：纯内存运算，**不依赖任何外部服务**
- **零 GC 压力**：循环内只有基本类型计算，**无对象分配**（v2.4 重点）
- **filter 是按位与**：先过滤再算距离，**比"算完再过滤"省 10x ~ 1000x**
- **Top-K 堆自带剪枝**：TopK 满了之后，差于堆顶的候选直接丢弃，**不需要全排序**

---

## 图 2：文本检索（searchByText）

```mermaid
sequenceDiagram
    autonumber
    participant U as 调用方
    participant C as VectorLiteDebugController
    participant EC as VectorEngineClientImpl
    participant EP as EmbeddingProvider
    participant HEP as HttpEmbeddingProvider
    participant EMB as 外部 Embedding 服务
    participant LE as LocalVectorEngine
    participant LS as LocalVectorStore

    U->>C: POST /stores/{name}/search/text<br/>{queryText, topK, filter?, minScore?}
    C->>EC: searchByText(request)
    
    EC->>EC: 校验 request / storeName / queryText 非空
    EC->>LE: getStore(storeName)
    LE-->>EC: LocalVectorStore
    
    EC->>EC: 取模型名:<br/>store.definition.embeddingModel<br/>→ properties.embedding.defaultModel
    
    alt embeddingProvider == null OR modelName == null
        EC-->>C: 抛 IllegalStateException<br/>("No EmbeddingProvider or<br/>embedding model configured")
        C-->>U: 500 Internal Server Error
    end
    
    EC->>EP: embed(modelName, version, queryText)
    EP->>HEP: embed(...)
    HEP->>HEP: resolveModelConfig(modelName)
    
    alt 模型配置缺失
        HEP-->>EC: 抛 IllegalArgumentException<br/>("No configuration found for<br/>embedding model [xxx]")
        EC-->>C: 异常冒泡
        C-->>U: 500 Internal Server Error
    end
    
    HEP->>EMB: HTTP POST {model, version, input}
    
    alt 服务不可达 / 4xx / 5xx
        EMB-->>HEP: 连接失败 或 非 200
        HEP-->>EC: 抛 RuntimeException<br/>("HTTP Embedding request failed")
        EC-->>C: 异常冒泡
        C-->>U: 500 Internal Server Error
    end
    
    EMB-->>HEP: 200 OK + JSON
    HEP->>HEP: EmbeddingResponseParserFactory<br/>识别 DATA / SINGLE / ARRAY
    HEP-->>EC: List<Float> floatList
    
    EC->>EC: floatList → float[] vector<br/>request.setQueryVector(vector)
    
    Note over EC,LS: ▼ 此后与"向量检索"完全相同 ▼
    EC->>LS: search(request)
    LS-->>EC: List<VectorSearchResult>
    EC-->>C: List<VectorSearchResult>
    C-->>U: 200 OK + JSON
```

### 文本检索 vs 向量检索

| 维度 | 向量检索 | 文本检索 |
|---|---|---|
| **HTTP 调用次数** | 0（纯内存） | 1（前端）+ 1（后端 Embedding） |
| **延迟组成** | 计算耗时 | 网络 + Embedding 推理 + 计算 |
| **依赖** | 无 | 后端必须配 `veclite.embedding.models` |
| **前端额外配置** | 无 | 需配 `embedSource` + `embedUrl` 或后端 Embedding |
| **失败模式** | 几乎不会失败 | 500（最常见于 Embedding 服务不可用） |

### 500 错误的常见根因（按概率排）

1. **后端没配 `veclite.embedding.models`** —— `IllegalArgumentException: No configuration found for embedding model [xxx]`
2. **Embedding 服务 URL 不可达** —— `RuntimeException: HTTP Embedding request failed ... Connection refused`
3. **Embedding 服务需要鉴权** —— VecLite 不自动加 `Authorization` header，**直接 401**
4. **CORS 拦截** —— 浏览器层面就被拦，**请求根本没发出去**（不是 500，是 network error）
5. **响应格式不在支持列表** —— `data` / `embedding` / 顶层数组 三种都不匹配

---

## 图 3：混合检索（hybridSearch）

```mermaid
sequenceDiagram
    autonumber
    participant U as 调用方
    participant EC as VectorEngineClientImpl

    U->>EC: hybridSearch(request)
    
    alt queryVector == null AND queryText != null
        EC->>EC: 走 searchByText 分支
        Note over EC: 即"图 2"的完整链路
    else 其他情况
        EC->>EC: 走 searchByVector 分支
        Note over EC: 即"图 1"的纯内存路径
    end
    
    EC-->>U: List<VectorSearchResult>
```

### 当前实现（v2.4）

`VectorEngineClientImpl.java:143-148`：

```java
public List<VectorSearchResult> hybridSearch(VectorSearchRequest request) {
    if (request.getQueryVector() == null && request.getQueryText() != null) {
        return searchByText(request);   // 文本 fallback
    }
    return searchByVector(request);     // 向量 fallback
}
```

**这并不是真正的"混合"**——只是根据入参**二选一**：
- 没传向量但传了文本 → 走文本（自动向量化）
- 其他情况 → 走向量

**真正的"混合检索"语义**（向量相似度 + 关键字匹配 + metadata 过滤加权）在当前 v2.4 **未实现**，是 roadmap 项。

---

## 三种检索的"该不该用"

| 场景 | 推荐 | 理由 |
|---|---|---|
| **RAG 检索** | 文本检索 | 用户输入自然语言，需要自动向量化 |
| **已知 query embedding 的批量回灌** | 向量检索 | 跳过 Embedding 调用，**延迟最低** |
| **前端 demo** | 文本检索 | 用户体验最好，"输入文字就出结果" |
| **后端服务间调用** | 向量检索 | 服务自己算 embedding，避免重复调用 |
| **混合检索需求** | 暂用文本检索 + metadata filter 替代 | 等 v2.5+ 的真正混合实现 |
