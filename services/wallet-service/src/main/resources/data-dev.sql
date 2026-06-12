-- wallet-service banks 마스터 시드 (dev·stage 공통 — 양쪽 yml의 spring.sql.init.data-locations로 실행).
-- admin-service data-dev.sql과 동일 패턴:
--   ddl-auto: update  → 테이블 생성
--   defer-datasource-initialization: true  → JPA 완료 후 실행
--   sql.init.mode: always + continue-on-error: true  → 중복 INSERT 무시
--
-- Mock 은행(gb-mock-bank seed.json)과 일치하는 16개 파트너 은행.
-- 국내 은행(is_domestic=1): KB국민, NH농협, 우리, 하나, 신한
-- 해외 은행(is_domestic=0): US 4개, VN 4개, PH 4개
-- 구(舊) 시뮬레이션 은행(Beaver Bank 900, Quokka Bank 901)은 비활성(is_active=0) 처리.

-- ── 구 시뮬레이션 은행 비활성화 ────────────────────────────────────────────────
UPDATE banks SET is_active = FALSE WHERE code IN ('900', '901');

-- ── 국내 은행 (KR) ─────────────────────────────────────────────────────────────
INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('004', 'KB국민은행', 'KR', TRUE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('011', 'NH농협은행', 'KR', TRUE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('020', '우리은행', 'KR', TRUE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('081', '하나은행', 'KR', TRUE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('088', '신한은행', 'KR', TRUE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

-- ── 미국 은행 (US) ──────────────────────────────────────────────────────────────
INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('WF', 'Wells Fargo', 'US', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('BOA', 'Bank of America', 'US', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('JPM', 'Chase (JPMorgan)', 'US', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('C', 'Citibank', 'US', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

-- ── 베트남 은행 (VN) ────────────────────────────────────────────────────────────
INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('VCB', 'Vietcombank', 'VN', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('BIDV', 'BIDV', 'VN', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('ICB', 'VietinBank', 'VN', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('TCB', 'Techcombank', 'VN', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

-- ── 필리핀 은행 (PH) ────────────────────────────────────────────────────────────
INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('BDO', 'BDO Unibank', 'PH', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('BPI', 'Bank of the Philippine Islands', 'PH', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('MBT', 'Metrobank', 'PH', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);

INSERT INTO banks (code, name, country, is_domestic, is_active)
VALUES ('UBP', 'UnionBank of the Philippines', 'PH', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), country = VALUES(country),
                        is_domestic = VALUES(is_domestic), is_active = VALUES(is_active);