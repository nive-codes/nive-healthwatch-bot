package com.nive.healthwatch.config;

import com.nive.healthwatch.domain.crypto.CryptoKeyHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * @author nive
 * @class CryptoConfig
 * @desc 부팅 시 health-watch.crypto.secret-key(base64 16/24/32B)를 읽어 CryptoKeyHolder 에 주입한다.
 *       미설정이면 경고 후 평문 폴백(디스코드 webhook 등 준시크릿이 평문 저장됨).
 * @since 2026-07-06
 */
@Slf4j
@Configuration
public class CryptoConfig {

    public CryptoConfig(@Value("${health-watch.crypto.secret-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("[Crypto] secret-key 미설정 — 준시크릿(디스코드 webhook 등)이 평문 저장됩니다. 프로덕션은 키 설정 권장.");
            return;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
                throw new IllegalArgumentException("AES 키 길이는 16/24/32 바이트여야 함(got " + raw.length + ")");
            }
            CryptoKeyHolder.set(new SecretKeySpec(raw, "AES"));
            log.info("[Crypto] AES-GCM 키 로드 완료({}bit)", raw.length * 8);
        } catch (Exception e) {
            throw new IllegalStateException("health-watch.crypto.secret-key 파싱 실패(base64 확인)", e);
        }
    }
}
