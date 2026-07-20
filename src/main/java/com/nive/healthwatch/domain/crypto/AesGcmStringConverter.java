package com.nive.healthwatch.domain.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * @author nive
 * @class AesGcmStringConverter
 * @desc 문자열 컬럼을 AES-GCM(랜덤 12B IV + 128b 태그)으로 암·복호화하는 JPA 컨버터. 저장형식은 "gcm:" + base64(iv||ct).
 *       키(CryptoKeyHolder)가 없으면 평문 그대로 저장/조회(개발 편의) — 프로덕션은 키 설정 권장.
 * @since 2026-07-06
 */
@Converter
public class AesGcmStringConverter implements AttributeConverter<String, String> {

    private static final String PREFIX = "gcm:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        SecretKey key = CryptoKeyHolder.key();
        if (key == null) {
            return attribute; // 키 미설정 → 평문 폴백
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 암호화 실패", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (!dbData.startsWith(PREFIX)) {
            return dbData; // 평문(폴백으로 저장된 값)
        }
        SecretKey key = CryptoKeyHolder.key();
        if (key == null) {
            return dbData; // 키 없이는 복호화 불가 — 원문 반환(운영자 인지용)
        }
        try {
            byte[] all = Base64.getDecoder().decode(dbData.substring(PREFIX.length()));
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_LEN];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 복호화 실패", e);
        }
    }
}
