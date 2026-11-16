package com.aiextract.service;

import com.aiextract.common.UploadLimits;
import com.aiextract.model.ExpertDocument;
import com.aiextract.model.Report;
import com.aiextract.model.SkillMaterial;
import com.aiextract.repository.ExpertDocumentRepository;
import com.aiextract.repository.ReportRepository;
import com.aiextract.repository.SkillMaterialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存量本地文件迁移：把素材/专家资料/报告 HTML 从本地磁盘搬到 OSS。
 *
 * <p>只做一次性运维用途，幂等：已是 OSS key 的行会跳过。迁移前先 dryRun 看数量。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageMigrationService {

    private static final int MAX_ROWS = 500;

    private final SkillMaterialRepository materialRepository;
    private final ExpertDocumentRepository expertDocumentRepository;
    private final ReportRepository reportRepository;
    private final FileStorageService fileStorageService;
    private final ReportStorageService reportStorageService;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> migrateLocal(boolean dryRun) {
        List<String> failures = new ArrayList<>();
        int materials = migrateMaterials(dryRun, failures);
        int experts = migrateExperts(dryRun, failures);
        int reports = migrateReports(dryRun, failures);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("materials", materials);
        result.put("experts", experts);
        result.put("reports", reports);
        result.put("failed", failures);
        log.info("本地文件迁移完成 dryRun={} materials={} experts={} reports={} failed={}",
                dryRun, materials, experts, reports, failures.size());
        return result;
    }

    private int migrateMaterials(boolean dryRun, List<String> failures) {
        int migrated = 0;
        for (SkillMaterial m : materialRepository.findAll(PageRequest.of(0, MAX_ROWS)).getContent()) {
            if (!isLegacyLocal(m.getFileUrl())) {
                continue;
            }
            if (dryRun) {
                migrated++;
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(Path.of(m.getFileUrl()));
                String ext = UploadLimits.extensionOf(m.getFileName());
                m.setFileUrl(fileStorageService.saveBytes(bytes, "material/legacy", ext,
                        m.getFileType() != null ? m.getFileType() : "application/octet-stream"));
                materialRepository.save(m);
                migrated++;
            } catch (Exception e) {
                failures.add("material " + m.getId() + ": " + e.getMessage());
            }
        }
        return migrated;
    }

    private int migrateExperts(boolean dryRun, List<String> failures) {
        int migrated = 0;
        for (ExpertDocument d : expertDocumentRepository.findAll(PageRequest.of(0, MAX_ROWS)).getContent()) {
            if (!isLegacyLocal(d.getFileUrl())) {
                continue;
            }
            if (dryRun) {
                migrated++;
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(Path.of(d.getFileUrl()));
                String ext = UploadLimits.extensionOf(d.getFileName());
                d.setFileUrl(fileStorageService.saveBytes(bytes, "expert/legacy", ext,
                        d.getFileType() != null ? d.getFileType() : "application/octet-stream"));
                expertDocumentRepository.save(d);
                migrated++;
            } catch (Exception e) {
                failures.add("expert " + d.getId() + ": " + e.getMessage());
            }
        }
        return migrated;
    }

    private int migrateReports(boolean dryRun, List<String> failures) {
        int migrated = 0;
        for (Report r : reportRepository.findAll(PageRequest.of(0, MAX_ROWS)).getContent()) {
            String path = r.getHtmlPath();
            if (path == null || path.startsWith("reports/")) {
                continue;
            }
            if (dryRun) {
                migrated++;
                continue;
            }
            try {
                String html = Files.readString(Path.of(path));
                reportStorageService.delete(path);
                r.setHtmlPath(reportStorageService.write(r.getId(), html));
                reportRepository.save(r);
                migrated++;
            } catch (Exception e) {
                failures.add("report " + r.getId() + ": " + e.getMessage());
            }
        }
        return migrated;
    }

    /** 只看本地路径：排除 text://、http(s)、以及已经是 OSS key 的值 */
    private boolean isLegacyLocal(String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        return ref.startsWith("/") || ref.startsWith("./") || ref.startsWith("data/")
                || ref.startsWith("file:") || ref.matches("^[A-Za-z]:[\\\\/].*");
    }
}
