package com.hexin.vector.lite.api;

import java.util.List;

public interface EmbeddingProvider {

    List<Float> embed(String modelName, String modelVersion, String text);

    List<List<Float>> embedBatch(String modelName, String modelVersion, List<String> texts);
}
