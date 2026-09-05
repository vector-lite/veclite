package veclite.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

@Schema(description = "Search request parameters")
public class VectorSearchRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "Target store name (set automatically from path)")
    private String storeName;

    @Schema(description = "Search mode")
    private SearchMode mode = SearchMode.VECTOR;

    @Schema(description = "Query vector for vector search")
    private float[] queryVector;

    @Schema(description = "Query text for text-based search (will be embedded)")
    private String queryText;

    @Schema(description = "Number of top results to return", example = "10")
    private int topK = 10;

    @Schema(description = "Minimum score threshold for results")
    private Float minScore;

    @Schema(description = "Metadata filter expression")
    private FilterExpression filter;

    @Schema(description = "Whether to normalize similarity score (e.g. cosine mapped to [0, 1] via (1 + cos) / 2 for Elasticsearch compatibility)", example = "false")
    private Boolean normalizeScore = false;

    @Schema(description = "Custom mathematical expression to calculate or normalize final score (e.g. 'score * 2.0 - 1.0', '(score + 1.0) / 2.0')", example = "score * 2.0 - 1.0")
    private String scoreExpression;

    public VectorSearchRequest() {}

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public SearchMode getMode() { return mode; }
    public void setMode(SearchMode mode) { this.mode = mode; }
    public float[] getQueryVector() { return queryVector; }
    public void setQueryVector(float[] queryVector) { this.queryVector = queryVector; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public Float getMinScore() { return minScore; }
    public void setMinScore(Float minScore) { this.minScore = minScore; }
    public FilterExpression getFilter() { return filter; }
    public void setFilter(FilterExpression filter) { this.filter = filter; }
    public Boolean getNormalizeScore() { return normalizeScore; }
    public void setNormalizeScore(Boolean normalizeScore) { this.normalizeScore = normalizeScore; }
    public String getScoreExpression() { return scoreExpression; }
    public void setScoreExpression(String scoreExpression) { this.scoreExpression = scoreExpression; }
}
