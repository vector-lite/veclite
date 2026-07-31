package veclite.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

@Schema(description = "Result of a delete operation")
public class DeleteResult implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "Number of documents deleted", example = "5")
    private int deletedCount;

    public DeleteResult() {}

    public DeleteResult(int deletedCount) { this.deletedCount = deletedCount; }

    public int getDeletedCount() { return deletedCount; }
    public void setDeletedCount(int deletedCount) { this.deletedCount = deletedCount; }
}
