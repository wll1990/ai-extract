package com.aiextract.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
/**
 * AES-256-GCM 加解密 — 保护合作方 SK 存储安全。
 * 即使 DB 泄露，没有 aes-key 也无法解密 SK。
 */
@Component
public class PartnerCrypto {

    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    public PartnerCrypto(@Value("${partner.aes-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /** 加密 → Base64(IV + ciphertext) */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("SK 加密失败", e);
        }
    }

    /** 解密 Base64(IV + ciphertext) → 明文 */
    public String decrypt(String encrypted) {
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[GCM_IV_LEN];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(combined, GCM_IV_LEN, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LEN, iv));
            return new String(cipher.doFinal(ciphertext));
        } catch (Exception e) {
            throw new RuntimeException("SK 解密失败", e);
        }
    }

    /** 生成新的 SK */
    public String generateSK() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 验证给定的明文 SK 是否匹配存储的密文 */
    public boolean matches(String plainSK, String encryptedSK) {
        try {
            return plainSK.equals(decrypt(encryptedSK));
        } catch (Exception e) {
            return false;
        }
    }
}
