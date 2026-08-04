-- 알림 서비스 전용 database. 01(정산)과 같은 패턴 — 컨테이너 최초 초기화 시 1회 실행되므로
-- 기존 컨테이너에는 docker compose rm -sf mysql && docker compose up -d mysql 로 재생성해 적용한다.
CREATE DATABASE IF NOT EXISTS pay_notification CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON pay_notification.* TO 'pay'@'%';
FLUSH PRIVILEGES;
