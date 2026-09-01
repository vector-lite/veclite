package veclite.persistence;

/**
 * Store 对应的数据库物理资源句柄。物理名称由持久化适配器生成，业务层不得自行拼接。
 */
public record StorePersistenceHandle(String storeName, String physicalName) {
    public StorePersistenceHandle {
        StoreNameValidator.validate(storeName);
        if (physicalName == null || physicalName.isBlank()) {
            throw new IllegalArgumentException("physicalName must not be blank");
        }
    }
}
