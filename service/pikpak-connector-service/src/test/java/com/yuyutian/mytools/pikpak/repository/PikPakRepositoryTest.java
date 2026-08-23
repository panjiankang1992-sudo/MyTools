package com.yuyutian.mytools.pikpak.repository;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;

/** PikPak 仓储与增量 schema 测试。 */
@JdbcTest
@Import(PikPakRepository.class)
class PikPakRepositoryTest {
    @Autowired
    private PikPakRepository repository;

    /** V2 路由字段可以在 V1 之后正常写入和读取。 */
    @Test
    void shouldRegisterAccountWithServerOnlyRoutes() {
        RegisterAccountRequest request = new RegisterAccountRequest("main", UUID.randomUUID(),
            "secret://pikpak/main", "pikpak_remote", "offline", "ready", false);

        Account account = repository.registerAccount(request);

        assertThat(account.remoteKey()).isEqualTo("pikpak_remote");
        assertThat(repository.registerAccount(request).id()).isEqualTo(account.id());
    }
}
