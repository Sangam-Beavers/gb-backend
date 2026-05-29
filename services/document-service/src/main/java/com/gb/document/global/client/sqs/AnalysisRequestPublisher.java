package com.gb.document.global.client.sqs;

/**
 * retry 시 Lambda A를 재트리거하기 위한 분석 요청 발행기.
 *
 * <p>최초 분석 요청은 사용자 PUT 업로드의 S3 이벤트로 Lambda A가 자동 트리거되지만, 재시도는
 * 본체가 명시적으로 메시지를 publish해 Lambda A를 다시 깨워야 한다. 사용자에게 재업로드를 요구하지
 * 않고 S3 원본을 재사용하는 정책(플랜 §6 — retry).
 */
public interface AnalysisRequestPublisher {

    /**
     * 분석 재요청 메시지 발행.
     *
     * @param documentPublicId 대상 문서 public_id (분석 결과 매칭 키)
     * @param userPublicId     소유자 public_id (Lambda 측 로깅/검증용)
     * @param s3Key            기존 S3 오브젝트 키 (Lambda가 같은 원본을 다시 처리)
     */
    void publishRetry(String documentPublicId, String userPublicId, String s3Key);
}
