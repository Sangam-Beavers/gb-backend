package com.gb.community.domain.report.entity;

/** 신고 처리 상태. */
public enum ReportStatus {
    /** 처리 대기 중 (기본값). */
    PENDING,
    /** 콘텐츠 삭제로 처리 완료. */
    RESOLVED_DELETED,
    /** 관리자가 무효 처리. */
    DISMISSED
}
