package com.yuyutian.mytools.automation.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MagnetLinkTest {
    @Test
    void acceptsEncodedBtihAndPreservesTrackers() {
        String query = "?xt=urn%3Abtih%3A" + "a".repeat(40) + "&tr=https%3A%2F%2Ft.example";
        assertThat(MagnetLink.normalize("MAGNET:" + query)).isEqualTo("magnet:" + query);
    }

    @Test
    void rejectsMissingHashAndAmbiguousIdentity() {
        assertThatThrownBy(() -> MagnetLink.normalize("magnet:?dn=empty"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MagnetLink.normalize("magnet:?xt=urn:btih:" + "a".repeat(40)
                + "&xt=urn:btih:" + "b".repeat(40))).isInstanceOf(IllegalArgumentException.class);
    }
}
