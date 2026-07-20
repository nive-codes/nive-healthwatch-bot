package com.nive.healthwatch.domain.crypto;

import javax.crypto.SecretKey;

/**
 * @author nive
 * @class CryptoKeyHolder
 * @desc AES-GCM 키의 정적 보관소. JPA AttributeConverter 는 Spring 이 아니라 Hibernate 가 생성하므로
 *       DI 대신 부팅 시 CryptoConfig 가 여기에 키를 주입하고, 컨버터는 static 으로 읽는다.
 *       키가 없으면(null) 컨버터는 평문 폴백한다.
 * @since 2026-07-06
 */
public final class CryptoKeyHolder {

    private static volatile SecretKey key;

    private CryptoKeyHolder() {
    }

    public static void set(SecretKey k) {
        key = k;
    }

    public static SecretKey key() {
        return key;
    }
}
