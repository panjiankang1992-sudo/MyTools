package com.yuyutian.mytools.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AesEncryptUtilsTest {

    @Test
    void shouldRejectMissingEncryptionKey() {
        assertThrows(IllegalStateException.class, () -> AesEncryptUtils.requireValidKey(""));
    }

    @Test
    void shouldDecryptPreviousKeyDuringRotation() {
        String previousKey = AesEncryptUtils.generateKey();
        String currentKey = AesEncryptUtils.generateKey();
        String ciphertext = AesEncryptUtils.encrypt("credential", previousKey);

        assertEquals("credential", AesEncryptUtils.decryptWithKeyRing(ciphertext, currentKey, previousKey));
    }
}
