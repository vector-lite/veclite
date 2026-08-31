package veclite.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreManager;
import veclite.embedding.EmbeddingModelRegistry;
import veclite.embedding.EmbeddingService;
import veclite.config.VectorLiteProperties;
import veclite.model.*;
import veclite.persistence.VectorPersistenceStorage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${veclite.web.base-path:/veclite/api/v1}")
@ConditionalOnProperty(name = "veclite.web.enabled", havingValue = "true")
@CrossOrigin(origins = "*")
@Tag(name = "VecLite", description = "Local vector search engine endpoints")
public class VectorLiteDebugController {

    private final VectorEngineClient client;
    private final VectorStoreManager storeManager;
    private final EmbeddingService embeddingService;
    private final VectorPersistenceStorage persistence;
    private final EmbeddingModelRegistry embeddingRegistry;

    public VectorLiteDebugController(VectorEngineClient client, VectorStoreManager storeManager,
                                     EmbeddingService embeddingService, VectorPersistenceStorage persistence,
                                     EmbeddingModelRegistry embeddingRegistry) {
        this.client = client;
        this.storeManager = storeManager;
        this.embeddingService = embeddingService;
        this.persistence = persistence;
        this.embeddingRegistry = embeddingRegistry;
    }

    @Operation(summary = "Save (create or update) an embedding data source")
    @PostMapping("/embedding/models")
    public Map<String, String> saveEmbeddingModel(@RequestBody EmbeddingModelInfo model) {
        embeddingRegistry.save(toModelConfig(model));
        // 数据源补配后恢复启动时因缺模型被跳过的存量 Store
        client.rediscoverPersistedStores();
        return success();
    }

    @Operation(summary = "Set an embedding data source as the default model")
    @PostMapping("/embedding/models/{name}/default")
    public Map<String, String> setDefaultEmbeddingModel(
            @Parameter(description = "Model name") @PathVariable String name,
            @Parameter(description = "Model version; omitted resolves to the primary version of the name")
            @RequestParam(required = false) String version) {
        embeddingRegistry.saveDefault(name, version);
        return success();
    }

    @Operation(summary = "Delete a managed embedding data source (yml built-in models cannot be deleted)")
    @DeleteMapping("/embedding/models/{name}")
    public Map<String, String> deleteEmbeddingModel(
            @Parameter(description = "Model name") @PathVariable String name,
            @Parameter(description = "Model version; omitted resolves to the primary version of the name")
            @RequestParam(required = false) String version) {
        embeddingRegistry.delete(name, version);
        return success();
    }

    private VectorLiteProperties.ModelConfig toModelConfig(EmbeddingModelInfo model) {
        if (model == null || model.getName() == null || model.getName().isBlank()) {
            throw new IllegalArgumentException("Embedding model name is required");
        }
        VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
        config.setName(model.getName().trim());
        config.setVersion(model.getVersion());
        config.setProvider(model.getProvider());
        config.setUrl(model.getUrl());
        config.setApiKey(model.getApiKey());
        config.setDimension(model.getDimension());
        config.setTimeoutMillis(model.getTimeoutMillis());
        config.setBatchSize(model.getBatchSize());
        return config;
    }

    @Operation(summary = "List all vector stores")
    @GetMapping("/stores")
    public List<String> listStores() {
        return storeManager.listStores();
    }

    @Operation(summary = "List all vector stores with details (dimension, metric, docCount, etc.)")
    @GetMapping("/stores/_details")
    public List<VectorStoreStats> listStoresWithDetails() {
        List<String> names = storeManager.listStores();
        List<VectorStoreStats> result = new java.util.ArrayList<>(names.size());
        for (String name : names) {
            try {
                result.add(client.stats(name));
            } catch (Exception e) {
                VectorStoreStats fallback = new VectorStoreStats();
                fallback.setStoreName(name);
                result.add(fallback);
            }
        }
        return result;
    }

    @Operation(summary = "Create a new vector store")
    @PostMapping("/stores/{storeName}")
    public Map<String, String> createStore(
            @Parameter(description = "Name of the store to create") @PathVariable String storeName,
            @RequestBody VectorStoreDefinition definition) {
        client.createStore(storeName, definition);
        return success();
    }

    @Operation(summary = "Get stats for a vector store")
    @GetMapping("/stores/{storeName}/stats")
    public VectorStoreStats stats(
            @Parameter(description = "Store name") @PathVariable String storeName) {
        return client.stats(storeName);
    }

    @Operation(summary = "Delete a vector store")
    @DeleteMapping("/stores/{storeName}")
    public Map<String, String> dropStore(
            @Parameter(description = "Store name") @PathVariable String storeName) {
        // 同时删除持久化数据（快照目录 / 文档真相源），防止重启后已删除的 Store 复活
        persistence.deleteStore(storeName);
        storeManager.dropStore(storeName);
        return success();
    }

    @Operation(summary = "Upsert a single document into a store")
    @PostMapping("/stores/{storeName}/documents")
    public Map<String, String> upsert(
            @Parameter(description = "Store name") @PathVariable String storeName,
            @RequestBody VectorDocument document) {
        client.upsert(storeName, document);
        return success();
    }

    @Operation(summary = "Upsert a batch of documents into a store (text-only docs are auto-embedded)")
    @PostMapping("/stores/{storeName}/documents/batch")
    public Map<String, String> upsertBatch(
            @Parameter(description = "Store name") @PathVariable String storeName,
            @RequestBody List<VectorDocument> documents) {
        client.upsertBatch(storeName, documents);
        return success();
    }

    @Operation(summary = "Get a single document by ID (including its vector)")
    @GetMapping("/stores/{storeName}/documents/{documentId}")
    public VectorDocument getDocument(
            @Parameter(description = "Store name") @PathVariable String storeName,
            @Parameter(description = "Document ID") @PathVariable String documentId) {
        return client.getDocument(storeName, documentId);
    }

    @Operation(summary = "List documents in a vector store")
    @GetMapping("/stores/{storeName}/documents")
    public VectorDocumentPage listDocuments(
            @Parameter(description = "Store name") @PathVariable String storeName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return client.listDocuments(storeName, page, size);
    }

    @Operation(summary = "Search by vector in a store")
    @PostMapping("/stores/{storeName}/search/vector")
    public List<VectorSearchResult> searchByVector(
            @Parameter(description = "Store name") @PathVariable String storeName,
            @RequestBody VectorSearchRequest request) {
        request.setStoreName(storeName);
        request.setMode(SearchMode.VECTOR);
        return client.searchByVector(request);
    }

    @Operation(summary = "Search by text in a store (text will be embedded)")
    @PostMapping("/stores/{storeName}/search/text")
    public List<VectorSearchResult> searchByText(
            @Parameter(description = "Store name") @PathVariable String storeName,
            @RequestBody VectorSearchRequest request) {
        request.setStoreName(storeName);
        request.setMode(SearchMode.TEXT);
        return client.searchByText(request);
    }

    @Operation(summary = "Delete documents by ID list")
    @DeleteMapping("/stores/{storeName}/documents")
    public DeleteResult deleteByIds(
            @Parameter(description = "Store name") @PathVariable String storeName,
            @RequestBody List<String> ids) {
        return client.deleteByIds(storeName, ids);
    }

    @Operation(summary = "Reload store data from persistence")
    @PostMapping("/stores/{storeName}/reload")
    public Map<String, String> reload(
            @Parameter(description = "Store name") @PathVariable String storeName) {
        client.reload(storeName);
        return success();
    }

    @Operation(summary = "Persist the latest in-memory data for a store")
    @PostMapping("/stores/{storeName}/refresh")
    public Map<String, String> refresh(
            @Parameter(description = "Store name") @PathVariable String storeName) {
        client.refresh(storeName);
        return success();
    }

    @Operation(summary = "List configured embedding service endpoints")
    @GetMapping("/embedding/models")
    public List<EmbeddingModelInfo> listEmbeddingModels() {
        return embeddingService.listModels();
    }

    private Map<String, String> success() {
        return Map.of("status", "SUCCESS");
    }

    /** 参数类异常统一映射为 400 并透出原因，便于前端 toast 展示 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        return Map.of("error", e.getMessage() == null ? "Invalid request" : e.getMessage());
    }
}
