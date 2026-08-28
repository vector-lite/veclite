package veclite.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreManager;
import veclite.model.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    public VectorLiteDebugController(VectorEngineClient client, VectorStoreManager storeManager) {
        this.client = client;
        this.storeManager = storeManager;
    }

    @Operation(summary = "List all vector stores")
    @GetMapping("/stores")
    public List<String> listStores() {
        return storeManager.listStores();
    }

    @Operation(summary = "List all vector stores with details (dimension, metric, docCount, storageSource, etc.)")
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
                fallback.setStorageSource("UNKNOWN");
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

    private Map<String, String> success() {
        return Map.of("status", "SUCCESS");
    }
}
