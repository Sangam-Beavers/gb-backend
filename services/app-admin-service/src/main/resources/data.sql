-- ============================================================
-- 개발 환경 초기 seed 데이터 (application-dev.yml에서만 로드됨)
-- INSERT IGNORE: 이미 있으면 건너뜀 → 서버 재시작해도 중복 없음
-- ============================================================

-- ── 수수료 정책 (EXCHANGE: 환전 수수료, CASHOUT: 외부 은행 출금 수수료) ────
-- TRANSFER(앱내 송금)는 무료 고정이라 정책 행 없음.
INSERT IGNORE INTO fee_policies
    (public_id, service_type, fee_type, fee_value, min_fee, max_fee, currency, active, created_at, updated_at)
VALUES
    ('fp-0001-0000-0000-000000000001', 'EXCHANGE', 'PERCENT',  1.5000, 500.0000,  5000.0000, 'KRW', true,  NOW(), NOW()),
    ('fp-0002-0000-0000-000000000002', 'CASHOUT',  'PERCENT',  2.0000, 1000.0000, 10000.0000,'KRW', true,  NOW(), NOW());

-- ── 서비스 설정 ───────────────────────────────────────────────
INSERT IGNORE INTO service_settings
    (public_id, setting_key, setting_value, description, active, created_at, updated_at)
VALUES
    ('ss-0001-0000-0000-000000000001', 'MAINTENANCE_MODE',       'false',    '점검 모드 여부 (true면 앱 전체 점검 화면)',                        true,  NOW(), NOW()),
    ('ss-0002-0000-0000-000000000002', 'MAX_TRANSFER_AMOUNT',    '5000000',  '1회 최대 송금 금액 (KRW 기준)',                                   true,  NOW(), NOW()),
    ('ss-0003-0000-0000-000000000003', 'MIN_TRANSFER_AMOUNT',    '1000',     '1회 최소 송금 금액 (KRW 기준)',                                   true,  NOW(), NOW()),
    ('ss-0004-0000-0000-000000000004', 'MAX_DAILY_TRANSFER',     '10000000', '1일 최대 송금 총액 (KRW 기준)',                                   true,  NOW(), NOW()),
    ('ss-0005-0000-0000-000000000005', 'DOC_ANALYSIS_CREDIT',    '3',        '신규 가입자 기본 서류 분석 크레딧 수',                             true,  NOW(), NOW()),
    ('ss-0006-0000-0000-000000000006', 'EXCHANGE_RATE_REFRESH_MIN', '5',     '환율 갱신 주기 (분)',                                            true,  NOW(), NOW());
