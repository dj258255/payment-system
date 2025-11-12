package com.beomsu.pay.shared.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlindIndexTest {

    @Test
    @DisplayName("같은 평문은 같은 해시 (동등 검색 가능)")
    void deterministic() {
        assertThat(BlindIndex.hash("110-1234-567890", "k"))
                .isEqualTo(BlindIndex.hash("110-1234-567890", "k"));
    }

    @Test
    @DisplayName("다른 평문은 다른 해시")
    void differs() {
        assertThat(BlindIndex.hash("a", "k")).isNotEqualTo(BlindIndex.hash("b", "k"));
    }
}
