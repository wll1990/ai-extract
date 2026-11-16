package com.aiextract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 一次性存量迁移开关：`app.storage.migrate-local-on-start=true` 时启动即把本地文件搬 OSS。
 *
 * <p>默认关闭；生产运维在重启命令里临时注入，跑完再正常重启，避免每次启动重复扫描。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.migrate-local-on-start", havingValue = "true")
public class StorageMigrationRunner implements ApplicationRunner {

    private final StorageMigrationService storageMigrationService;

    @Override
    public void run(ApplicationArguments args) {
        log.warn("检测到 app.storage.migrate-local-on-start=true，开始迁移存量本地文件到 OSS");
        storageMigrationService.migrateLocal(false);
    }
}
