package com.gb.document.domain.document.entity;

import com.gb.document.global.common.entity.BaseEntity;
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
 * 분석 대상 문서(=업로드된 계약서 등)의 메타 엔티티 — <b>최소 스켈레톤</b>.
 *
 * <p>여기는 <b>이유진 영역</b>(분석 파이프라인)이며, 챗봇 컨트롤러(심규보)는 권한 검증을 위해
 * {@link com.gb.document.domain.document.repository.DocumentRepository#findByPublicId(String)} 한
 * 메서드만 사용한다.
 *
 * <p>AI-WORK-SPLIT.md §3-③ — "공유 지점: DocumentRepository.findByPublicId. 유진이 먼저 만들어
 * 인터페이스를 고정하고, 규보는 그걸 가져다 쓴다."에 따라 <b>공유 인터페이스를 깨지 않는 한</b>
 * 유진이 이 엔티티에 필드를 자유롭게 추가할 수 있다(예: file_name, document_type, original_size 등).
 *
 * <p>현재는 챗봇 권한 검증에 필요한 최소 필드(public_id + user_public_id)만 보유한다.
 */
@Entity
@Getter
@Table(name = "documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 식별자(UUID). conventions §0 — 내부 id는 응답/URL에 노출 금지. */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /**
     * 문서 소유자(member-service users.public_id 논리 참조).
     * CLAUDE.md §7 — MSA 경계를 넘는 회원 참조는 user_public_id(UUID)로, 물리 FK 금지.
     */
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    @Builder
    private Document(String publicId, String userPublicId) {
        this.publicId = publicId;
        this.userPublicId = userPublicId;
    }
}
