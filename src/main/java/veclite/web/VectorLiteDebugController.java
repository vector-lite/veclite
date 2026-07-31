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

@RestController
@RequestMapping("${veclite.web.base-path:/veclite/api/v1}")
@ConditionalOnProperty(name = "veclite.web.enabled", havingValue = "true")
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

    @Operation(summary = "Create a new vector store")
    @PostMapping("/stores/{storeName}")
    public String createStore(
            @Parameter(description = "Name of the store to create") @PathVariable String storeName,
            @RequestBody VectorStoreDefinition definition) {
        client.createStore(storeName, definition);
        return "SUCCESS";
    }

    @Operation(summary = "Get stats for a vector store")
    @GetMapping("/stores/{storeName}/stats")
    public VectorStoreStats stats(
            @Parameter(description = "Store name") @PathVariable String storeName) {
        return client.stats(storeName);
    }

    @Operation(summary = "Upsert a single document into a store")
    @PostMapping("/stores/{storeName}/documents")
    public String upsert(
            @Parameter(description = "Store name") @PathVariable String storeName,
            @RequestBody VectorDocument document) {
        client.upsert(storeName, document);
        return "SUCCESS";
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
    public String reload(
            @Parameter(description = "Store name") @PathVariable String storeName) {
        client.reload(storeName);
        return "SUCCESS";
    }
}
