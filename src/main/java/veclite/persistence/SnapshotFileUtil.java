package veclite.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 快照文件的原子目录交换工具。
 * <p>提供 {@code .tmp} 目录写入 → 目录级原子替换 → 失败回滚的公共能力。
 * <p>被 {@link SnapshotFileStorage} 和 {@link OssSnapshotStorage} 共同使用，
 * 保证"3 个文件"全部写入完成才对外可见，杜绝崩溃时出现新旧混搭的中间态。
 */
public final class SnapshotFileUtil {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFileUtil.class);

    private SnapshotFileUtil() {}

    /**
     * 准备一个干净的临时目录 {@code parent/<storeName>.tmp/}，供调用方写入快照文件。
     * <p>如果之前残留有同名 .tmp 目录，会先删除再重建。
     *
     * @return 创建好的临时目录
     */
    public static File prepareTempDir(File parent, String storeName) {
        File tmpDir = new File(parent, storeName + ".tmp");
        if (tmpDir.exists()) {
            deleteRecursively(tmpDir);
        }
        if (!tmpDir.mkdirs()) {
            throw new RuntimeException("Failed to create temp dir: " + tmpDir.getAbsolutePath());
        }
        return tmpDir;
    }

    /**
     * 目录级原子交换：
     * <ol>
     *   <li>将旧目录重命名为 {@code storeName.bak}（原子）</li>
     *   <li>将临时目录重命名为正式目录（原子）</li>
     *   <li>删除 .bak</li>
     * </ol>
     * 任何一步失败都会尝试回滚旧目录，保证磁盘上始终存在一份完整可用的快照。
     *
     * @param tmpDir   临时目录（已写入完整 3 文件）
     * @param storeDir 正式目录
     * @param storeName store 名（用于构造 .bak 名）
     */
    public static void swapDirectoryAtomic(File tmpDir, File storeDir, String storeName) throws IOException {
        File bakDir = new File(storeDir.getParentFile(), storeName + ".bak");
        if (bakDir.exists()) {
            deleteRecursively(bakDir);
        }
        boolean movedOld = false;
        if (storeDir.exists()) {
            Files.move(storeDir.toPath(), bakDir.toPath());
            movedOld = true;
        }
        try {
            Files.move(tmpDir.toPath(), storeDir.toPath());
        } catch (IOException moveEx) {
            // 回滚：把旧目录移回原位
            if (movedOld && !storeDir.exists()) {
                Files.move(bakDir.toPath(), storeDir.toPath());
            }
            throw moveEx;
        }
        if (movedOld) {
            deleteRecursively(bakDir);
        }
    }

    /**
     * 递归删除目录或文件。容错：内部异常不抛。
     */
    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File c : files) {
                    deleteRecursively(c);
                }
            }
        }
        if (!f.delete()) {
            log.warn("Failed to delete {}", f.getAbsolutePath());
        }
    }
}
