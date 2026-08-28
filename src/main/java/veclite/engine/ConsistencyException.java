package veclite.engine;

/**
 * 内部数据一致性断言失败时抛出的异常（v2.4 § 4.4）。
 *
 * <p>表示 {@link LocalVectorStore} 内 vec / payload / idIndex
 * 三者 size 已经不一致——内存已脏,应阻止落盘扩散。
 */
public class ConsistencyException extends RuntimeException {

    public ConsistencyException(String message) {
        super(message);
    }

    public ConsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
