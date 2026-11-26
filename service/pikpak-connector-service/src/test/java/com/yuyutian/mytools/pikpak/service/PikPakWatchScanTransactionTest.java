package com.yuyutian.mytools.pikpak.service;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.yuyutian.mytools.pikpak.repository.PikPakRepository;
import com.yuyutian.mytools.pikpak.repository.PikPakWatchRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 验证定时扫描入口的批次写入事务。 */
@JdbcTest(properties = {"pikpak.enabled=true",
    "spring.datasource.url=jdbc:h2:mem:pikpak_watch_scan_transaction;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PikPakWatchService.class, PikPakWatchRepository.class, PikPakRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PikPakWatchScanTransactionTest {
    @Autowired
    private PikPakWatchService service;
    @Autowired
    private PikPakRepository accounts;
    @Autowired
    private PikPakWatchRepository watches;
    @MockBean
    private RclonePikPakClient connector;

    /** 文件快照写入失败时保留先前快照和批次版本。 */
    @Test
    void shouldRollBackScanAllWhenReplacingItemsFails() {
        Account account = accounts.registerAccount(new RegisterAccountRequest("scan-transaction-test",
            UUID.randomUUID(), "secret://scan-transaction-test", "pikpak", "offline", "ready", true, 120));
        service.register(new RegisterWatcherRequest(account.id(), "watch", "backup", true, 120, true));
        WatchBatch original = watches.insert(account.id(), "album", "original", Instant.now(), false);
        List<RemoteItem> originalItems = List.of(new RemoteItem("original", "album/original.mp4", 10, "time"));
        watches.replaceItems(original.id(), originalItems);
        when(connector.listWatchRoot("pikpak", "watch", Set.of())).thenReturn(List.of(
            new RemoteItem("duplicate", "album/a.mp4", 7, "time"),
            new RemoteItem("duplicate", "album/b.mp4", 9, "time")));

        assertThatThrownBy(service::scanAll).isInstanceOf(DuplicateKeyException.class);
        assertThat(watches.requireBatch(original.id())).isEqualTo(original);
        assertThat(watches.items(original.id())).containsExactlyElementsOf(originalItems);
    }
}
