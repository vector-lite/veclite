package veclite.persistence;

import java.util.Set;
import java.util.regex.Pattern;

/** 数据库物理表/集合名称的统一校验规则。 */
public final class StoreNameValidator {
    /** PostgreSQL/Mongo 均支持短横；名称只需避开注入字符并满足标识符长度限制。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,62}");
    private static final Set<String> RESERVED = Set.of(
            "veclite_store_meta", "veclite_embedding_model", "veclite_embedding_default");

    private StoreNameValidator() { }

    public static String validate(String storeName) {
        if (storeName == null || !SAFE_NAME.matcher(storeName).matches()) {
            throw new IllegalArgumentException(
                    "storeName must start with a letter and contain only letters, digits, underscores or hyphens (max 63 chars)");
        }
        if (RESERVED.contains(storeName.toLowerCase())) {
            throw new IllegalArgumentException("storeName is reserved: " + storeName);
        }
        return storeName;
    }
}
