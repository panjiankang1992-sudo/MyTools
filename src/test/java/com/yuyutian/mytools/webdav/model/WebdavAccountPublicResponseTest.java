package com.yuyutian.mytools.webdav.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebdavAccountPublicResponseTest {

    @Test
    void shouldNeverSerializeEncryptedPassword() throws Exception {
        WebdavAccountPublicResponse response = new WebdavAccountPublicResponse(
                1L, 2L, "nextcloud", "https://dav.example.com", "user", true);

        String json = new ObjectMapper().writeValueAsString(response);

        assertFalse(json.toLowerCase().contains("password" + "cipher"));
        assertFalse(json.contains("encryptedPassword"));
        assertTrue(json.contains("passwordSet"));
    }
}
