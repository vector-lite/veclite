package veclite.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.List;

@Schema(description = "Metadata filter for search/delete operations")
public class FilterExpression implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "Comparison operator")
    public enum Operator {
        EQ,
        IN,
        GT,
        LT,
        AND,
        OR
    }

    @Schema(description = "Metadata field name to filter on", example = "category")
    private String field;

    @Schema(description = "Operator to apply")
    private Operator operator;

    @Schema(description = "Value for EQ operator", example = "news")
    private Object value;

    @Schema(description = "Values for IN operator")
    private List<Object> values;

    @Schema(description = "Child expressions for AND/OR operators")
    private List<FilterExpression> children;

    public FilterExpression() {}

    public static FilterExpression eq(String field, Object value) {
        FilterExpression expr = new FilterExpression();
        expr.field = field; expr.operator = Operator.EQ; expr.value = value;
        return expr;
    }

    public static FilterExpression in(String field, List<Object> values) {
        FilterExpression expr = new FilterExpression();
        expr.field = field; expr.operator = Operator.IN; expr.values = values;
        return expr;
    }

    public static FilterExpression and(FilterExpression... children) {
        return compound(Operator.AND, children);
    }

    public static FilterExpression or(FilterExpression... children) {
        return compound(Operator.OR, children);
    }

    private static FilterExpression compound(Operator operator, FilterExpression... children) {
        FilterExpression expr = new FilterExpression();
        expr.operator = operator;
        expr.children = children == null ? List.of() : List.of(children);
        return expr;
    }

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public Operator getOperator() { return operator; }
    public void setOperator(Operator operator) { this.operator = operator; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    public List<Object> getValues() { return values; }
    public void setValues(List<Object> values) { this.values = values; }
    public List<FilterExpression> getChildren() { return children; }
    public void setChildren(List<FilterExpression> children) { this.children = children; }
}
