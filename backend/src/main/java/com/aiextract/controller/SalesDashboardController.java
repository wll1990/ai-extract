package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.service.SalesDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 销售个人看板（SA-07）：只看当前登录人自己的数据。
 *
 * <p>权限沿用 {@code /sales/**} 规则（SALES_REVIEW / SALES_REVIEW_VIEW_ALL / SALES_LEAD_MANAGE）。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@RestController
@RequestMapping("/sales/dashboard")
@RequiredArgsConstructor
public class SalesDashboardController {

    private final SalesDashboardService dashboardService;

    @GetMapping("/mine")
    public ApiResponse<Map<String, Object>> mine() {
        return ApiResponse.success(dashboardService.mine());
    }
}
