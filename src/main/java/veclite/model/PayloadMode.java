package veclite.model;

/**
 * Payload 存储模式枚举。
 * <ul>
 *   <li><b>MEMORY</b>：全量在堆内紧凑数组常驻存储 (CompactPayloadStorage)</li>
 *   <li><b>MMAP</b>：磁盘 MMap 追加文件与延迟按 Top-K 提取 (MMapPayloadStorage)</li>
 * </ul>
 */
public enum PayloadMode {
    MEMORY,
    MMAP
}
