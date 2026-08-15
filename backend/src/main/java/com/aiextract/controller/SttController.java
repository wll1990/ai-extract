package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.SttService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 语音识别接口 — 录音后一次性识别。
 *
 * <p>前端上传 WAV，返回识别文本 {@code {"text": "..."}}。鉴权由 SecurityConfig 统一处理
 * （{@code POST /stt/recognize} 需有效 JWT）。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@RestController
@RequiredArgsConstructor
public class SttController {

    private final SttService sttService;

    @PostMapping("/stt/recognize")
    public ApiResponse<Map<String, Object>> recognize(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "音频文件为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "读取音频文件失败", e);
        }
        String text = sttService.recognize(bytes);
        return ApiResponse.success(Map.of("text", text));
    }
}
