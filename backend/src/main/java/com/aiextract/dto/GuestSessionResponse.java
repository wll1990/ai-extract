package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * C 端会话响应DTO — 游客发证 / 注册升级 / 登录 / me 共用
 *
 * <p>token 在 me 接口中为 null（探测身份不重签）；
 * remaining/limit 仅 status=guest 时有值。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestSessionResponse {

    /** C 端 JWT（Bearer 使用；me 接口不重签为 null） */
    private String token;

    /** C 端用户ID（app_user.id，访客身份本体） */
    private String userId;

    /** 昵称 */
    private String nickname;

    /** 状态: guest / registered */
    private String status;

    /** 剩余免费条数（仅游客） */
    private Long remaining;

    /** 免费额度上限（仅游客） */
    private Integer limit;

    /** 剩余免费萃取次数（仅已注册 C 端用户） */
    private Long extractionRemaining;

    /** 免费萃取总次数（仅已注册 C 端用户） */
    private Integer extractionLimit;
}
