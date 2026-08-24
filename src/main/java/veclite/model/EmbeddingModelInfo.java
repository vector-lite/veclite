package veclite.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

@Schema(description = "Configuration info of a managed embedding model endpoint")
public class EmbeddingModelInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "Model name (unique key)", example = "bge-m3")
    private String name;

    @Schema(description = "Model version", example = "1")
    private String version;

    @Schema(description = "Provider type", example = "http")
    private String provider;

    @Schema(description = "Embedding service endpoint URL")
    private String url;

    @Schema(description = "Request timeout in milliseconds")
    private int timeoutMillis;

    @Schema(description = "Max texts embedded per request")
    private int batchSize;

    @Schema(description = "Whether this model is the global default model")
    private boolean defaultModel;

    public EmbeddingModelInfo() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public int getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public boolean isDefaultModel() { return defaultModel; }
    public void setDefaultModel(boolean defaultModel) { this.defaultModel = defaultModel; }
}
