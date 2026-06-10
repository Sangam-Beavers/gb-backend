package com.gb.member.global.security.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JPA {@link AttributeConverter} — {@code String} 컬럼을 AES-256-GCM으로 자동 암복호.
 *
 * <p>적용 방식: 대상 엔티티 필드에 {@code @Convert(converter = EncryptedStringConverter.class)}로 명시한다
 * ({@code autoApply = false} — 모든 문자열 컬럼을 자동 암호화하면 안 되므로). 사용 예:
 * {@link com.gb.member.domain.verification.entity.UserVerification#documentNumber}.
 *
 * <p>Hibernate 6 + Spring Boot 3은 {@code SpringBeanContainer}를 통해 컨버터를 Spring 빈으로 인식하므로
 * 이 클래스가 {@code @Component}로 등록되면 {@link AesGcmCryptoService}가 정상 주입된다.
 */
@Slf4j
@Converter(autoApply = false)
@Component
@RequiredArgsConstructor
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final AesGcmCryptoService cryptoService;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cryptoService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        try {
            return cryptoService.decrypt(dbData);
        } catch (IllegalStateException e) {
            // 키 불일치(dev 환경 키 교체 등) 또는 데이터 손상 — 복호화 실패 시 null 반환.
            // 운영 환경에서 이 경고가 보이면 GB_CRYPTO_KEY 또는 데이터를 점검해야 한다.
            log.warn("[EncryptedStringConverter] 복호화 실패 — null 반환. 원인: {}", e.getMessage());
            return null;
        }
    }
}
