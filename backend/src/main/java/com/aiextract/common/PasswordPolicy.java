package com.aiextract.common;

import com.aiextract.exception.BusinessException;

/**
 * 密码强度统一规则 —— B 端注册/改密与 C 端注册必须同源，避免一处收紧一处漏。
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /** 至少 8 位，且同时包含字母和数字；上限 64 位防止超长输入拖慢 BCrypt */
    public static void validate(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException(400, "密码至少 8 位");
        }
        if (password.length() > 64) {
            throw new BusinessException(400, "密码不能超过 64 位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(400, "密码需同时包含字母和数字");
        }
    }
}
