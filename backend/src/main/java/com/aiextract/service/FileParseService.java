package com.aiextract.service;

import com.aiextract.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * 文件解析服务 —— 调 ai-service /internal/parse-file 提取纯文本（通用，跨管道复用）。
 *
 * <p>知识/案例上传的异步萃取统一经此解析，不复用 {@code MaterialCleaningService.parseFile}
 * （那个方法绑定 SkillMaterial 实体、写素材状态，语义不对）。</p>
 *
 * <p>文件已存 OSS：本服务用对象 key 生成预签名 URL，交由 ai-service 自行下载解析，
 * 不再要求 backend 与 ai-service 同机共享本地磁盘。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileParseService {

    private final WebClient webClient;
    private final OssService ossService;

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    /**
     * 解析文件为纯文本。
     *
     * <p>这里不设文本上限：素材、知识、案例各有自己的字数上限，统一在调用方按用途校验，
     * 避免一份产线文档被素材的 10 万字上限误杀。</p>
     *
     * @param objectKey OSS 对象 key（{@link FileStorageService#saveFile} 的返回值）
     * @param fileName  文件名（辅助判断类型）
     * @return 纯文本；解析失败或返回无 text 时为 null
     * @throws BusinessException 图片/音频等需人工补充内容时
     */
    public String parseToText(String objectKey, String fileName) {
        // 生成预签名 GET URL（含签名，ai-service 无需额外凭据即可下载）
        ParseResult result = parseUrl(ossService.generatePresignedUrl(objectKey), fileName);
        if (result == null) {
            return null;
        }
        if (result.needsManual()) {
            throw new BusinessException(400, "图片或音频文件，需人工补充文字内容");
        }
        return result.text();
    }

    /**
     * 解析「存储引用」：新数据是 OSS object key（需预签名），存量数据可能是本地绝对路径或完整 URL。
     *
     * <p>素材、专家资料、知识、案例统一走这里，避免每个调用方各写一套路径判断。</p>
     *
     * @param storedRef OSS key / 本地路径 / http(s) URL
     * @param fileName  文件名（辅助判断类型）
     * @return 解析结果；无文本时返回 null
     */
    public ParseResult parseStored(String storedRef, String fileName) {
        if (storedRef == null || storedRef.isBlank()) {
            return null;
        }
        return isDirectPathOrUrl(storedRef)
                ? parseUrl(storedRef, fileName)
                : parseKey(storedRef, fileName);
    }

    /** OSS key → 预签名 URL → 解析；保留 needsManual 语义，供图片/音频走人工补录。 */
    public ParseResult parseKey(String objectKey, String fileName) {
        return parseUrl(ossService.generatePresignedUrl(objectKey), fileName);
    }

    /** 本地路径（绝对/相对/./data/）/ file: / http(s) URL 直接交给 ai-service；其余视为 OSS key */
    private boolean isDirectPathOrUrl(String ref) {
        return ref.startsWith("/") || ref.startsWith("./") || ref.startsWith("../")
                || ref.startsWith("data/") || ref.startsWith("file:")
                || ref.startsWith("http://") || ref.startsWith("https://")
                || ref.matches("^[A-Za-z]:[\\\\/].*");
    }

    /** 解析结果：needsManual 时 text 可能为空，由调用方决定提示方式 */
    public record ParseResult(String text, boolean needsManual) {}

    /**
     * 直接解析一个可访问路径（本地路径或预签名 URL）。
     * 素材上传走本地存储路径，知识/案例走 OSS 预签名 URL，底层共用同一 HTTP 契约。
     */
    public ParseResult parseUrl(String fileUrl, String fileName) {
        Map<String, Object> response = webClient.post()
                .uri(aiServiceUrl + "/internal/parse-file")
                .bodyValue(Map.of("file_path", fileUrl, "file_name", fileName))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(120))
                .block();
        if (response == null || !response.containsKey("text")) {
            return null;
        }
        return new ParseResult((String) response.get("text"),
                Boolean.TRUE.equals(response.get("needs_manual")));
    }
}
