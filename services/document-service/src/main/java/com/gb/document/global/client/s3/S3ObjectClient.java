package com.gb.document.global.client.s3;

/**
 * S3 오브젝트 존재 확인 클라이언트.
 *
 * <p>retry(POST /documents/{id}/retry)가 "원본이 S3에 남아 있는가"로 분기하기 위해 사용한다
 * (api-spec.md §5 — 원본 유지 시 Lambda 재트리거, 미존재 시 재업로드 URL 재발급).
 * Pre-signed URL 발급({@link S3PresignedUrlClient})과는 별개 관심사라 인터페이스를 분리한다.
 */
public interface S3ObjectClient {

    /**
     * 해당 키의 오브젝트가 업로드 버킷에 존재하는지 확인한다(HeadObject).
     *
     * @param key S3 오브젝트 키 (예: {@code original/2026-06-04/{publicId}/contract.pdf})
     * @return 존재하면 true, 404(NoSuchKey)면 false
     */
    boolean objectExists(String key);
}
