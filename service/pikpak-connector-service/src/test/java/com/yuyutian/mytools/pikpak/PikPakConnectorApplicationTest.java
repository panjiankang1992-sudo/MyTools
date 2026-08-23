package com.yuyutian.mytools.pikpak;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 验证独立 schema 能从空库启动。 */
@SpringBootTest
class PikPakConnectorApplicationTest {
    /** 加载完整应用和 Flyway 迁移。 */
    @Test
    void contextLoadsFromEmptySchema() { }
}
