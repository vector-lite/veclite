package com.hexin.vector.lite.model;

import java.io.Serializable;

public class DeleteResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private int deletedCount;

    public DeleteResult() {
    }

    public DeleteResult(int deletedCount) {
        this.deletedCount = deletedCount;
    }

    public int getDeletedCount() {
        return deletedCount;
    }

    public void setDeletedCount(int deletedCount) {
        this.deletedCount = deletedCount;
    }
}
