package com.beomsu.pay.reconciliation;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PG 정산 파일(CSV) 파서 — 외부 기록({@link ExternalRecord}) 인입 경로.
 *
 * <p>대사 엔진은 내부 기록 vs 외부 기록을 대조하지만, 실제 PG 정산 파일을 {@code ExternalRecord}로
 * 만드는 경로가 없어 사실상 트리거되지 않았다. 이 파서가 그 구멍을 메운다.
 *
 * <p><b>헤더 기반</b>이라 컬럼 순서·부가 컬럼(거래일시·승인번호 등)에 영향받지 않는다. 첫 줄에서
 * orderNo/amount 컬럼의 위치를 컬럼명으로 찾고(대소문자·공백 무시, 한글/스네이크 별칭 허용),
 * 두 컬럼이 없으면 {@code INVALID_SETTLEMENT_FILE}로 거부한다.
 *
 * <p><b>견고성</b>: 정산 파일에는 요약행·공백행·깨진 행이 섞일 수 있다. 개별 데이터행이 불량이어도
 * (orderNo 빈 값 / amount 파싱 실패) 전체를 깨지 않고 그 행만 건너뛰며 스킵 수를 집계한다.
 * CSV는 단순 콤마 분리로 처리한다(orderNo/amount에는 콤마가 흔치 않음 — 금액의 천단위 콤마는 제거).
 */
@Component
public class PgSettlementCsvParser {

    /** orderNo 컬럼 별칭 — 정규화(소문자·공백제거) 후 비교. */
    private static final Set<String> ORDER_NO_ALIASES = Set.of("orderno", "order_no", "주문번호");
    /** amount 컬럼 별칭 — 정규화 후 비교. */
    private static final Set<String> AMOUNT_ALIASES = Set.of("amount", "settle_amount", "settleamount", "결제금액");

    /**
     * 파싱 결과 — 유효한 외부 기록 목록과 건너뛴(불량) 행 수.
     *
     * @param records 매칭 엔진에 넘길 외부 기록
     * @param skipped orderNo 빈 값 또는 amount 파싱 실패로 건너뛴 데이터행 수(요약행 등 방어)
     */
    public record ParseResult(List<ExternalRecord> records, int skipped) {
    }

    /**
     * 정산 CSV를 파싱해 외부 기록으로 변환한다. 스트림은 이 메서드가 닫는다.
     *
     * @throws ReconciliationException 파일이 비었거나 필수 컬럼(orderNo/amount)이 없으면 {@code INVALID_SETTLEMENT_FILE}
     */
    public ParseResult parse(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String headerLine = readNextNonBlank(reader);
            if (headerLine == null) {
                throw new ReconciliationException("INVALID_SETTLEMENT_FILE", "정산 파일이 비어 있습니다.");
            }
            String[] header = stripBom(headerLine).split(",", -1);
            int orderNoIdx = findColumn(header, ORDER_NO_ALIASES);
            int amountIdx = findColumn(header, AMOUNT_ALIASES);
            if (orderNoIdx < 0 || amountIdx < 0) {
                throw new ReconciliationException("INVALID_SETTLEMENT_FILE",
                        "정산 파일에 필수 컬럼(orderNo, amount)이 없습니다. 헤더: " + headerLine);
            }
            int maxIdx = Math.max(orderNoIdx, amountIdx);

            List<ExternalRecord> records = new ArrayList<>();
            int skipped = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue; // 공백행은 노이즈로 무시(스킵 카운트에 넣지 않음)
                }
                String[] cols = line.split(",", -1);
                if (cols.length <= maxIdx) {
                    skipped++; // 컬럼 수 부족(요약행 등)
                    continue;
                }
                String orderNo = cols[orderNoIdx].trim();
                if (orderNo.isEmpty()) {
                    skipped++; // orderNo 빈 행
                    continue;
                }
                Long amount = parseAmount(cols[amountIdx]);
                if (amount == null) {
                    skipped++; // amount 파싱 실패
                    continue;
                }
                records.add(new ExternalRecord(orderNo, amount));
            }
            return new ParseResult(records, skipped);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 다음 비공백 줄을 읽는다(파일 앞머리의 빈 줄을 건너뛰고 헤더를 찾는다). */
    private static String readNextNonBlank(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    /** 헤더 컬럼명을 정규화해 별칭 집합과 일치하는 첫 컬럼 인덱스를 찾는다. 없으면 -1. */
    private static int findColumn(String[] header, Set<String> aliases) {
        for (int i = 0; i < header.length; i++) {
            if (aliases.contains(normalize(header[i]))) {
                return i;
            }
        }
        return -1;
    }

    /** 대소문자·양끝공백·내부공백을 무시한 정규화(컬럼명 비교용). */
    private static String normalize(String raw) {
        return raw.trim().toLowerCase().replaceAll("\\s", "");
    }

    /** 금액 토큰 파싱 — 천단위 콤마·공백 제거 후 long. 실패하거나 음수면 null(스킵 신호). */
    private static Long parseAmount(String raw) {
        String cleaned = raw.trim().replace(",", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(cleaned);
            return value < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** UTF-8 BOM이 앞에 붙은 파일(엑셀 저장물)의 헤더 첫 컬럼명이 깨지지 않게 제거. */
    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }
}
