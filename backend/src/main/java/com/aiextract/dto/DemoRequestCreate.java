package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 门户「预约演示」提交。 */
@Getter
@Setter
public class DemoRequestCreate {

    @NotBlank(message = "请填写公司名称")
    @Size(max = 200, message = "公司名称过长")
    private String companyName;

    @NotBlank(message = "请填写称呼")
    @Size(max = 100, message = "称呼过长")
    private String contactName;

    @Size(max = 100, message = "角色过长")
    private String role;

    @Size(max = 50, message = "团队规模过长")
    private String teamSize;

    @NotBlank(message = "请填写联系方式")
    @Size(max = 200, message = "联系方式过长")
    private String contact;

    @Size(max = 2000, message = "补充说明过长")
    private String message;

    /** 蜜罐字段：正常用户不会填写，填了视为机器人 */
    private String website;
}
