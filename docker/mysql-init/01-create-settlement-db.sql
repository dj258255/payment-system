-- 정산 서비스 전용 database. pay 유저에게 권한을 부여해 로컬에서는 계정을 공유한다
-- (운영이라면 인스턴스·계정 모두 분리). mysql 컨테이너 최초 초기화 시 1회 실행된다 —
-- 기존 컨테이너에는 적용되지 않으므로 docker compose down 후 up으로 재생성한다.
CREATE DATABASE IF NOT EXISTS pay_settlement CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON pay_settlement.* TO 'pay'@'%';
FLUSH PRIVILEGES;
