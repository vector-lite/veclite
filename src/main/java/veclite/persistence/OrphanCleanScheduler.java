package veclite.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import veclite.config.VectorLiteProperties;
import veclite.persistence.meta.VectorMetadataRepository;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 孤儿清理调度器：定时扫描 PG / OSS / 本地磁盘，清掉不在 PG 里的孤儿文件。
 *
 * 防 9 pod 重复：用 PG advisory_lock 让只有一个 pod 真正执行清理。
 */
@Component
@ConditionalOnBean(VectorMetadataRepository.class)
public class OrphanCleanScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrphanCleanScheduler.class);
    private static final long ADVISORY_LOCK_ID = 0x7EC11A0E;  // 固定 ID，9 pod 共用

    private final VectorMetadataRepository metadataRepository;
    private final JdbcTemplate jdbc;
    private final VectorLiteProperties properties;

    @Autowired(required = false)
    private VectorPersistenceStorage persistence;

    public OrphanCleanScheduler(VectorMetadataRepository metadataRepository,
                                JdbcTemplate jdbc,
                                VectorLiteProperties properties) {
        this.metadataRepository = metadataRepository;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${veclite.orphan-clean.interval-seconds:86400}000",  // 默认 1 天
            initialDelayString = "${veclite.orphan-clean.interval-seconds:86400}000"
    )
    public void cleanOrphans() {
        if (jdbc == null) {
            log.debug("OrphanCleanScheduler skipped: no JdbcTemplate");
            return;
        }
        // 抢 advisory lock；抢不到说明其他 pod 在跑
        Boolean acquired;
        try {
            acquired = jdbc.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_ID);
        } catch (DataAccessException e) {
            log.warn("OrphanCleanScheduler: advisory_lock failed: {}", e.getMessage());
            return;
        }
        if (acquired == null || !acquired) {
            log.debug("OrphanCleanScheduler: another pod holds the lock, skipping");
            return;
        }
        try {
            Set<String> alive = new HashSet<>();
            metadataRepository.listAll().forEach(m -> alive.add(m.getStoreName()));
            cleanOss(alive);
            cleanLocal(alive);
        } finally {
            try {
                jdbc.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, ADVISORY_LOCK_ID);
            } catch (Exception ignore) { /* best effort */ }
        }
    }

    /** 列 OSS 上所有 storeName（基于 keyPrefix），对比 PG，删孤儿 */
    private void cleanOss(Set<String> alive) {
        if (persistence == null) return;
        try {
            List<String> ossStores = persistence.listStoreNames();
            for (String name : ossStores) {
                if (!alive.contains(name)) {
                    log.info("OrphanClean: OSS has orphan store [{}], deleting", name);
                    persistence.deleteStore(name);
                }
            }
        } catch (Exception e) {
            log.error("OrphanClean OSS scan failed: {}", e.getMessage());
        }
    }

    /** 列本地 ./data/vec/ 下所有子目录，对比 PG，删孤儿 */
    private void cleanLocal(Set<String> alive) {
        String base = properties.getStorage().getSnapshotFile().getBasePath();
        Path root = Paths.get(base);
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(p -> {
                String name = p.getFileName().toString();
                if (!alive.contains(name)) {
                    log.info("OrphanClean: local has orphan dir [{}], deleting", name);
                    deleteRecursively(p.toFile());
                }
            });
        } catch (Exception e) {
            log.error("OrphanClean local scan failed: {}", e.getMessage());
        }
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        if (!f.delete()) {
            log.warn("OrphanClean: failed to delete [{}]", f.getAbsolutePath());
        }
    }
}
