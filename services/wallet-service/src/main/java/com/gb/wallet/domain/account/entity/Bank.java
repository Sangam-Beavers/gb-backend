package com.gb.wallet.domain.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 은행 마스터. 국내/해외/시뮬레이션 은행(Beaver/Quokka) 모두 포함한다.
 * 외부 노출은 {@code code} + {@code name}만 사용하며, 내부 PK {@code id}는 응답/URL에 노출하지 않는다.
 *
 * <p><b>BaseEntity 미상속 — 의도된 예외</b>. 사유:
 * <ul>
 *   <li>{@code banks}는 {@code data.sql}로 주입되는 정적 마스터 데이터라 변경 추적이 불필요하다.</li>
 *   <li>{@code docs/database.md} §3 {@code banks} 스키마에 {@code created_at/updated_at} 컬럼이 정의돼 있지 않다.</li>
 * </ul>
 * 다른 엔티티({@code bank_accounts}, {@code wallets} 등)는 {@link com.gb.wallet.global.common.entity.BaseEntity}
 * 상속이 표준이며, 같은 예외를 다른 도메인 엔티티에 임의 적용하지 말 것.
 */
@Entity
@Getter
@Table(name = "banks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 은행 코드(국내 표준 코드 또는 시뮬레이션 코드). */
    @Column(name = "code", length = 20, nullable = false, unique = true)
    private String code;

    /** 은행명. */
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /** 국가 코드(예: KR). */
    @Column(name = "country", length = 10, nullable = false)
    private String country;

    /** 국내 은행 여부. */
    @Column(name = "is_domestic", nullable = false)
    private boolean isDomestic;

    /** 연동 활성화 여부. */
    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Builder
    private Bank(String code, String name, String country, boolean isDomestic, boolean isActive) {
        this.code = code;
        this.name = name;
        this.country = country;
        this.isDomestic = isDomestic;
        this.isActive = isActive;
    }
}
