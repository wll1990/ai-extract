package com.aiextract;

import com.aiextract.util.CsvWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 生成：中文 BOM、引号/逗号/换行转义、公式注入防护。
 */
class CsvWriterTest {

    @Test
    void escapesCommaQuoteAndNewline() {
        String csv = body(new CsvWriter().append("a,b", "say \"hi\"", "line1\nline2"));

        assertEquals("\"a,b\",\"say \"\"hi\"\"\",\"line1\nline2\"\n", csv);
    }

    @Test
    void prefixesFormulaInjection() {
        String csv = body(new CsvWriter().append("=1+1", "+86", "-5", "@cmd"));

        assertEquals("'=1+1,'+86,'-5,'@cmd\n", csv);
    }

    @Test
    void keepsPlainChineseAsIs() {
        String csv = body(new CsvWriter().append("张三", "已通过"));

        assertEquals("张三,已通过\n", csv);
    }

    @Test
    void toBytesStartsWithUtf8Bom() {
        byte[] bytes = new CsvWriter().append("中").toBytes();

        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        assertTrue(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8).contains("中"));
    }

    private String body(CsvWriter writer) {
        byte[] bytes = writer.toBytes();
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }
}
