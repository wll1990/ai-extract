package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.service.ImBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 我的 IM 绑定 —— 生成绑定码、查看/解绑。
 *
 * <p>绑定码 10 分钟有效；用户在 IM 里给机器人发「绑定 123456」完成。</p>
 */
@RestController
@RequestMapping("/me/im")
@RequiredArgsConstructor
public class MeImController {

    private final ImBindingService bindingService;

    @PostMapping("/bind-code")
    public ApiResponse<Map<String, Object>> createCode() {
        ImBindingService.BindCode code = bindingService.createCode();
        return ApiResponse.success(Map.of(
                "code", code.code(),
                "expiresAt", code.expiresAt().toString(),
                "instruction", "在飞书/企微/钉钉里给机器人发送：绑定 " + code.code()));
    }

    @GetMapping("/bindings")
    public ApiResponse<List<ImBindingService.BindingView>> list() {
        return ApiResponse.success(bindingService.listMine());
    }

    @DeleteMapping("/bindings/{id}")
    public ApiResponse<Void> unbind(@PathVariable UUID id) {
        bindingService.unbind(id);
        return ApiResponse.success();
    }
}
