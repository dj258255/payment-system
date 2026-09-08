package com.beomsu.pay.settlement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;

import java.sql.*;

import static org.assertj.core.api.Assertions.*;

/**
 * <b>플랫폼 직판 정산이 같은 날짜에 두 번 생기는 것을 DB 가 막는지</b> 실 MySQL 에 묻는다.
 *
 * <p>여기가 한 번 뚫려 있었다. V37 이 유니크 키에 {@code seller_id} 를 더했는데,
 * MySQL 은 유니크 인덱스에서 <b>NULL 을 서로 다른 값으로 본다.</b> 그리고 이 서비스에서
 * NULL 은 곧 플랫폼 직판이라 <b>기본값</b>이다. 그래서 같은 (날짜, 통화, NULL) 이
 * 몇 줄이든 들어갔다.
 *
 * <p>그때 남아 있던 방어는 집계 전 존재 검사 하나뿐이었는데, 그건 이 프로젝트가
 * 인스턴스 둘을 띄워 <b>실제로 뚫은 바로 그 종류의 검사</b>다. 검사와 삽입 사이가 벌어지면
 * 둘 다 통과한다. V42 가 생성 컬럼으로 NULL 을 0 에 모아 제약을 완성했다.
 *
 * <p>이 테스트는 재는 것이 아니라 <b>고정하는</b> 것이다. 누가 제약을 다시 손대면 여기서 걸린다.
 */
@Tag("integration")
class SettlementNullSellerUniquenessMySqlTest {

    private static final String INSERT = """
            INSERT INTO settlements
              (settlement_date, currency, seller_id, gross_amount, fee_amount, fee_vat_amount,
               net_amount, item_count, status, payout_date, created_at)
            VALUES (?, 'KRW', ?, 1000, 0, 0, 1000, 1, 'CREATED', ?, NOW())
            """;

    @Test
    @DisplayName("판매자가 NULL(플랫폼 직판)이어도 같은 날짜 정산은 두 번 안 들어간다")
    void nullSellerIsStillUnique() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("uniq")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            try (Connection c = mysql.createConnection("")) {
                insert(c, "2099-01-01", null);

                assertThatThrownBy(() -> insert(c, "2099-01-01", null))
                        .as("NULL 판매자도 DB 가 마지막에 막아야 한다. 앱 가드는 레이스에 뚫린다")
                        .isInstanceOf(SQLIntegrityConstraintViolationException.class);

                // 판매자가 다르면 같은 날짜에 각각 생긴다. 제약이 과하게 막지 않는 것도 함께 고정한다.
                insert(c, "2099-01-01", 1L);
                insert(c, "2099-01-01", 2L);
                assertThat(count(c, "2099-01-01"))
                        .as("NULL 1건 + 판매자 2명 = 3건")
                        .isEqualTo(3);

                // 같은 판매자를 같은 날 두 번도 막는다.
                assertThatThrownBy(() -> insert(c, "2099-01-01", 1L))
                        .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            }
        }
    }

    private static void insert(Connection c, String date, Long sellerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, date);
            if (sellerId == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, sellerId);
            ps.setString(3, "2099-01-03");
            ps.executeUpdate();
        }
    }

    private static int count(Connection c, String date) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM settlements WHERE settlement_date = ?")) {
            ps.setString(1, date);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
