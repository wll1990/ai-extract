package com.aiextract.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 阶段改名/重排请求。
 *
 * <p>提交的顺序即新阶段顺序（后端重编 phase_no=1..n）；
 * phaseNo 集合必须与当前草稿版本中出现的阶段完全一致，缺项/多项一律 409。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingPhaseRequest {

    @NotEmpty(message = "阶段列表不能为空")
    @Valid
    private List<PhaseItem> phases;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhaseItem {

        @NotNull(message = "阶段序号不能为空")
        private Integer phaseNo;

        @Size(max = 100, message = "阶段名不能超过 100 字")
        private String phaseName;
    }
}
