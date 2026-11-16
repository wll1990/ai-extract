package com.aiextract;

import com.aiextract.config.PartnerCrypto;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 主密钥启动校验 —— P0-3。
 *
 * <p>仓库里不能再有可直接使用的默认密钥：缺失、非法 Base64、长度不对都必须启动失败。</p>
 */
class PartnerCryptoTest {

    @Test
    void missingKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> new PartnerCrypto(""));
        assertThrows(IllegalStateException.class, () -> new PartnerCrypto(null));
    }

    @Test
    void invalidBase64FailsFast() {
        assertThrows(IllegalStateException.class, () -> new PartnerCrypto("not-base64!!!"));
    }

    @Test
    void wrongKeyLengthFailsFast() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> new PartnerCrypto(shortKey));
    }

    @Test
    void validKeyEncryptsAndDecrypts() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        PartnerCrypto crypto = new PartnerCrypto(key);

        String encrypted = crypto.encrypt("sk-test");

        assertNotEquals("sk-test", encrypted);
        assertEquals("sk-test", crypto.decrypt(encrypted));
    }
}
