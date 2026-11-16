package com.aiextract.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 CSV 生成器 —— RFC4180 转义 + UTF-8 BOM + 公式注入防护。
 *
 * <p>Excel 打开无 BOM 的 UTF-8 CSV 会乱码；以 {@code = + - @} 开头的单元格
 * 会被 Excel 当公式执行，导出内容含用户输入（学员姓名/理由），必须加前缀 {@code '}。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
public class CsvWriter {

    private final StringBuilder body = new StringBuilder();
    private int rows;

    /** 追加一行；列数由调用方保证一致。 */
    public CsvWriter append(Object... values) {
        List<String> cells = new ArrayList<>(values.length);
        for (Object value : values) {
            cells.add(escape(value == null ? "" : String.valueOf(value)));
        }
        body.append(String.join(",", cells)).append('\n');
        rows++;
        return this;
    }

    /** 带 BOM 的 UTF-8 字节，可直接上传/落盘。 */
    public byte[] toBytes() {
        byte[] content = body.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[content.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(content, 0, withBom, 3, content.length);
        return withBom;
    }

    public int rowCount() {
        return rows;
    }

    static String escape(String raw) {
        String value = raw == null ? "" : raw;
        // 公式注入：首字符是 = + - @（允许前导空白）
        String trimmedLeading = value.stripLeading();
        if (!trimmedLeading.isEmpty() && "=+-@".indexOf(trimmedLeading.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
