package com.aiextract;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Assumptions;

/**
 * OSS 头像上传集成测试 — 上传 → 预签名 URL → HTTP 验证 — 不清理，保留文件供控制台查看。
 */
public class OssServiceTest {

    private static final String ENDPOINT = "oss-cn-beijing.aliyuncs.com";
    private static final String BUCKET = "ai-extract";
    private static final String AK = System.getenv("OSS_ACCESS_KEY_ID");
    private static final String SK = System.getenv("OSS_ACCESS_KEY_SECRET");
    private static final long EXPIRE_SECONDS = 365 * 24 * 3600L;

    @Test
    public void testUploadAndPresignedUrl() throws Exception {
        Assumptions.assumeTrue(AK != null && SK != null, "未设置 OSS_ACCESS_KEY_ID/SECRET 环境变量，跳过");
        File file = new File(System.getProperty("user.home"), "Downloads/1.png");
        assertTrue(file.exists(), "测试文件不存在: " + file.getAbsolutePath());
        byte[] content = Files.readAllBytes(file.toPath());

        OSS client = new OSSClientBuilder().build(ENDPOINT, AK, SK);
        String objectKey = "avatars/test/" + System.currentTimeMillis() + "_1.png";

        // 上传
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("image/png");
        metadata.setContentLength(content.length);
        client.putObject(new PutObjectRequest(BUCKET, objectKey,
                new ByteArrayInputStream(content), metadata));
        System.out.println("✅ 上传成功 key=" + objectKey + " size=" + content.length + "bytes");

        // 预签名 URL
        Date expiration = new Date(System.currentTimeMillis() + EXPIRE_SECONDS * 1000);
        URL presignedUrl = client.generatePresignedUrl(BUCKET, objectKey, expiration);
        System.out.println("🔗 " + presignedUrl);

        // 验证可访问
        HttpURLConnection conn = (HttpURLConnection) presignedUrl.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());
        assertEquals("image/png", conn.getHeaderField("Content-Type"));
        conn.disconnect();

        System.out.println("✅ HTTP 200 — 预签名 URL 可访问");
        System.out.println("📦 OSS 控制台路径: ai-extract / " + objectKey);
        System.out.println("🧹 未清理，请在控制台查看");

        client.shutdown();
    }
}
