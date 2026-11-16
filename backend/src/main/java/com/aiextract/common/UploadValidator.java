package com.aiextract.common;

import com.aiextract.exception.BusinessException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * 上传文件校验：大小、Content-Type、文件头（magic）。
 *
 * <p>只看扩展名/Content-Type 可被伪造，头像这类会公开展示的图片必须嗅探文件头。</p>
 */
public final class UploadValidator {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private UploadValidator() {}

    /** 校验并返回规范化后的扩展名（.jpg/.png/.webp）。 */
    public static String requireImage(MultipartFile file, long maxBytes) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择图片文件");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException(400, "图片不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException(400, "仅支持 jpg/png/webp 图片");
        }
        String ext;
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            ext = sniff(head);
        } catch (IOException e) {
            throw new BusinessException(400, "图片读取失败");
        }
        if (ext == null) {
            throw new BusinessException(400, "图片内容与格式不符");
        }
        return ext;
    }

    private static String sniff(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return ".png";
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return ".webp";
        }
        return null;
    }
}
