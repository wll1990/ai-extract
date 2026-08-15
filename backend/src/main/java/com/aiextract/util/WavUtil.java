package com.aiextract.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * WAV 音频解析工具 — 从 WAV 字节中提取 PCM 数据。
 *
 * <p>前端语音录入生成的是标准 RIFF/WAVE + PCM 16-bit 单声道 16kHz 的 WAV，
 * 这里只做最小解析：校验 RIFF/WAVE 头、遍历 chunk 找到 {@code data}、校验编码为 PCM。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
public final class WavUtil {

    private WavUtil() {
    }

    /**
     * 从 WAV 字节提取 PCM 数据（16-bit）。
     *
     * @param wavBytes WAV 文件字节
     * @return 纯 PCM 数据（不含 WAV 头）
     * @throws IllegalArgumentException 不是合法 WAV 或编码非 PCM/16-bit 时
     */
    public static byte[] toPcm(byte[] wavBytes) {
        ByteBuffer buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN);

        if (buf.remaining() < 12) {
            throw new IllegalArgumentException("音频数据过短，不是合法的 WAV 文件");
        }
        String riff = readAscii(buf, 4);
        buf.getInt(); // 文件大小（不校验）
        String wave = readAscii(buf, 4);
        if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) {
            throw new IllegalArgumentException("不是合法的 WAV 文件（缺少 RIFF/WAVE 头）");
        }

        int audioFormat = -1;
        int bitsPerSample = -1;
        byte[] pcm = null;

        while (buf.remaining() >= 8) {
            String chunkId = readAscii(buf, 4);
            int chunkSize = buf.getInt();

            if ("fmt ".equals(chunkId)) {
                audioFormat = buf.getShort() & 0xffff;
                buf.getShort(); // channels（前端固定单声道）
                buf.getInt();   // sampleRate（前端固定 16000）
                buf.getInt();   // byteRate
                buf.getShort(); // blockAlign
                bitsPerSample = buf.getShort() & 0xffff;
                // fmt chunk 标准为 16 字节，若有扩展则跳过剩余
                int consumed = 16;
                if (chunkSize > consumed) {
                    buf.position(buf.position() + (chunkSize - consumed));
                }
            } else if ("data".equals(chunkId)) {
                pcm = new byte[chunkSize];
                buf.get(pcm);
            } else {
                // 未知 chunk：跳过（WAV chunk 需按 2 字节对齐）
                buf.position(buf.position() + chunkSize + (chunkSize & 1));
            }
        }

        if (pcm == null) {
            throw new IllegalArgumentException("WAV 缺少 data chunk");
        }
        if (audioFormat != 1) {
            throw new IllegalArgumentException("仅支持 PCM 编码的 WAV，audioFormat=" + audioFormat);
        }
        if (bitsPerSample != 16) {
            throw new IllegalArgumentException("仅支持 16-bit PCM，bitsPerSample=" + bitsPerSample);
        }
        return pcm;
    }

    private static String readAscii(ByteBuffer buf, int len) {
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.US_ASCII);
    }
}
