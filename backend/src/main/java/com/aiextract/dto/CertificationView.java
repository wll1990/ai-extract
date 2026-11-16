package com.aiextract.dto;

import com.aiextract.model.TrainingCertification;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 认证视图 —— certify 响应、学员列表、批次详情、进度行共用同一形态。
 *
 * <p>字段名与空值策略固定：无认证记录时整个对象为 null，不用空串或缺失 key。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CertificationView {

    /** pending/confirmed/remediating/revoked */
    private String status;

    /** 确认人 ID */
    private String confirmedBy;

    /** 确认人姓名 */
    private String confirmedByName;

    /** 确认时间 */
    private LocalDateTime confirmedAt;

    /** 备注 */
    private String note;

    /** 撤销人 ID */
    private String revokedBy;

    /** 撤销人姓名 */
    private String revokedByName;

    /** 撤销时间 */
    private LocalDateTime revokedAt;

    /** 撤销理由 */
    private String revokeReason;

    /** 由实体 + 已解析的姓名构造；cert 为空时返回 null */
    public static CertificationView of(TrainingCertification cert, String confirmedByName) {
        return of(cert, confirmedByName, null);
    }

    public static CertificationView of(TrainingCertification cert, String confirmedByName,
                                       String revokedByName) {
        if (cert == null) {
            return null;
        }
        return CertificationView.builder()
                .status(cert.getStatus())
                .confirmedBy(cert.getConfirmedBy() != null ? cert.getConfirmedBy().toString() : null)
                .confirmedByName(confirmedByName)
                .confirmedAt(cert.getConfirmedAt())
                .note(cert.getNote())
                .revokedBy(cert.getRevokedBy() != null ? cert.getRevokedBy().toString() : null)
                .revokedByName(revokedByName)
                .revokedAt(cert.getRevokedAt())
                .revokeReason(cert.getRevokeReason())
                .build();
    }
}
