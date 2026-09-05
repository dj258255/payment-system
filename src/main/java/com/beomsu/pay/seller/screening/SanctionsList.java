package com.beomsu.pay.seller.screening;

import java.util.List;

/**
 * 대조할 제재·PEP 명단.
 *
 * <p><b>기본 구현은 비어 있다.</b> 실제 명단(OFAC SDN 등)은 외부에서 받아 갱신하는 것이고,
 * 이 저장소에 실어 두면 <b>낡은 명단이 코드와 함께 굳는다</b>. 갱신 주기가 코드 배포 주기에
 * 묶이면 그것 자체가 규제 위반이 된다.
 *
 * <p>그래서 이름만 받는 포트로 두고, 운영에서는 명단 제공자를 끼운다. 여기서 재는 것은
 * <b>명단의 내용이 아니라 매칭 방식</b>이다. 오탐은 명단이 아니라 매칭에서 나온다.
 */
public interface SanctionsList {

    /** 명단 항목 하나. */
    record Entry(String name, String program, String country) {}

    List<Entry> entries();

    /** 어느 판을 봤는지. 갱신 전후를 구별해야 "그때는 통과였다"를 댈 수 있다. */
    String version();
}
