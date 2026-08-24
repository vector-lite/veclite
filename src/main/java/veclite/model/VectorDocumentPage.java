package veclite.model;

import java.io.Serializable;
import java.util.List;

/**
 * A page of stored documents, intended for management and administration UIs.
 */
public class VectorDocumentPage implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<VectorDocument> items;
    private int page;
    private int size;
    private int total;

    public VectorDocumentPage() {
    }

    public VectorDocumentPage(List<VectorDocument> items, int page, int size, int total) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.total = total;
    }

    public List<VectorDocument> getItems() { return items; }
    public void setItems(List<VectorDocument> items) { this.items = items; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
}
