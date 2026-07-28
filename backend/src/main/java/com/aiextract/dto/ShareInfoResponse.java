package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 分享落地页信息响应DTO
 *
 * <p>GET /public/share/{shareCode} 返回。无凭证即可访问，
 * remaining/viewerStatus 仅在请求携带有效 C 端 token 时返回。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShareInfoResponse {

    /** 分身ID（前端聊天接口需要） */
    private String skillId;

    /** 分享短码 */
    private String shareCode;

    /** 销冠姓名 */
    private String ownerName;

    /** 销冠头衔 */
    private String ownerTitle;

    /** 头像URL */
    private String avatarUrl;

    /** 擅长标签 */
    private List<String> tags;

    /** 场景标签（含颗粒计数），对练开场用 */
    private List<Map<String, Object>> sceneTags;

    /** 游客免费消息额度 */
    private Integer guestLimit;

    /** 剩余免费条数（仅 viewer 为游客时返回） */
    private Long remaining;

    /** 访问者身份：guest / registered / null（未认证） */
    private String viewerStatus;

    /** 分身开场白 — 聊天页入场态展示 */
    private String openingMessage;

    /** 聚合互动统计（来自 skill 表缓存字段，无数据时各值为0） */
    private Map<String, Object> stats;
}
