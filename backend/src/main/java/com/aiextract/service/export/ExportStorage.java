package com.aiextract.service.export;

import java.nio.file.Path;

/**
 * 导出文件存储 —— local 单实例 / OSS 多实例。
 *
 * <p>filePath 一律存 key（对象键或相对路径），不存临时下载 URL。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
public interface ExportStorage {

    /** 写入文件，返回存储 key */
    String store(String key, byte[] content, String contentType);

    /** 预签名/直链；local 返回 null，由 Controller 直接读文件 */
    String presignedUrl(String key);

    /** 本地文件路径；OSS 返回 null */
    Path localPath(String key);
}
