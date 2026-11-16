package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** 运营更新「预约演示」跟进状态。 */
@Getter
@Setter
public class DemoRequestStatusUpdate {

    /** new / contacted / closed —— 与 demo_request.status 的 CHECK 约束保持一致 */
    @NotBlank(message = "请选择状态")
    @Pattern(regexp = "new|contacted|closed", message = "状态只能是 new / contacted / closed")
    private String status;
}
