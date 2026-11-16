package com.aiextract.service;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.CompanyRegisterCode;
import com.aiextract.repository.CompanyRegisterCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 企业注册码服务。
 *
 * <p>平台超管生成「企业管理员码」，企业管理员生成「员工码」——两者共用同一张表和同一套失效语义
 * （停用 / 过期 / 用尽），区别只在 defaultRole 和谁能生成。</p>
 */
@Service
@RequiredArgsConstructor
public class CompanyRegisterCodeService {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RNG = new SecureRandom();

    private final CompanyRegisterCodeRepository repository;

    /** 生成注册码：调用方决定角色、次数与有效期。 */
    @Transactional(rollbackFor = Exception.class)
    public CompanyRegisterCode create(UUID companyId, String defaultRole, int maxUses,
                                      LocalDateTime expiresAt, UUID createdBy) {
        CompanyRegisterCode code = CompanyRegisterCode.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .code(generateUniqueCode())
                .enabled(true)
                .maxUses(maxUses)
                .usedCount(0)
                .createdBy(createdBy)
                .createdAt(LocalDateTime.now())
                .expiresAt(expiresAt)
                .defaultRole(defaultRole)
                .build();
        repository.save(code);
        return code;
    }

    /** 企业自己的员工码列表（不含超管下发的管理员码）。 */
    public List<CompanyRegisterCode> listEmployeeCodes(UUID companyId) {
        return repository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(c -> "employee".equals(c.getDefaultRole()))
                .toList();
    }

    /** 取本企业的码；跨企业返回 404，防止枚举。 */
    public CompanyRegisterCode requireOwned(UUID companyId, UUID codeId) {
        CompanyRegisterCode code = repository.findById(codeId).orElse(null);
        if (code == null || !companyId.equals(code.getCompanyId())) {
            throw new BusinessException(404, "注册码不存在");
        }
        return code;
    }

    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(UUID companyId, UUID codeId, boolean enabled) {
        CompanyRegisterCode code = requireOwned(companyId, codeId);
        code.setEnabled(enabled);
        repository.save(code);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID companyId, UUID codeId) {
        repository.delete(requireOwned(companyId, codeId));
    }

    /** 生成全局唯一注册码。小概率冲突（31^8 空间）时重试 5 次后抛异常让调用方重试。 */
    private String generateUniqueCode() {
        for (int i = 0; i < 5; i++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int j = 0; j < CODE_LENGTH; j++) {
                sb.append(CODE_CHARS.charAt(RNG.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (repository.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new BusinessException(500, "生成注册码失败，请重试");
    }
}
