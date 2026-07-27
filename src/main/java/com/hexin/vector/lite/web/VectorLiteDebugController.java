package com.hexin.vector.lite.web;

import com.hexin.vector.lite.api.VectorEngineClient;
import com.hexin.vector.lite.api.VectorStoreDefinition;
import com.hexin.vector.lite.api.VectorStoreManager;
import com.hexin.vector.lite.model.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("${aime.vector.lite.web.base-path:/aime-vector-lite/api/v1}")
@ConditionalOnProperty(name = "aime.vector.lite.web.enabled", havingValue = "true")
public class VectorLiteDebugController {

    private final VectorEngineClient client;
    private final VectorStoreManager storeManager;

    public VectorLiteDebugController(VectorEngineClient client, VectorStoreManager storeManager) {
        this.client = client;
        this.storeManager = storeManager;
    }

    @GetMapping("/stores")
    public List<String> listStores() {
        return storeManager.listStores();
    }

    @PostMapping("/stores/{storeName}")
    public String createStore(@PathVariable String storeName, @RequestBody VectorStoreDefinition definition) {
        client.createStore(storeName, definition);
        return "SUCCESS";
    }

    @GetMapping("/stores/{storeName}/stats")
    public VectorStoreStats stats(@PathVariable String storeName) {
        return client.stats(storeName);
    }

    @PostMapping("/stores/{storeName}/documents")
    public String upsert(@PathVariable String storeName, @RequestBody VectorDocument document) {
        client.upsert(storeName, document);
        return "SUCCESS";
    }

    @PostMapping("/stores/{storeName}/search/vector")
    public List<VectorSearchResult> searchByVector(@PathVariable String storeName, @RequestBody VectorSearchRequest request) {
        request.setStoreName(storeName);
        request.setMode(SearchMode.VECTOR);
        return client.searchByVector(request);
    }

    @PostMapping("/stores/{storeName}/search/text")
    public List<VectorSearchResult> searchByText(@PathVariable String storeName, @RequestBody VectorSearchRequest request) {
        request.setStoreName(storeName);
        request.setMode(SearchMode.TEXT);
        return client.searchByText(request);
    }

    @DeleteMapping("/stores/{storeName}/documents")
    public DeleteResult deleteByIds(@PathVariable String storeName, @RequestBody List<String> ids) {
        return client.deleteByIds(storeName, ids);
    }

    @PostMapping("/stores/{storeName}/reload")
    public String reload(@PathVariable String storeName) {
        client.reload(storeName);
        return "SUCCESS";
    }
}
