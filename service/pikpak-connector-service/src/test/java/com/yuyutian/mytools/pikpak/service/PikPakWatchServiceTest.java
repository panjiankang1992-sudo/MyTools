package com.yuyutian.mytools.pikpak.service;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.yuyutian.mytools.pikpak.repository.PikPakRepository;
import com.yuyutian.mytools.pikpak.repository.PikPakWatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** PikPak watcher 稳定观察与归档状态机测试。 */
class PikPakWatchServiceTest {
    /** 相同批次达到稳定窗口后才对下载任务可见。 */
    @Test
    void shouldExposeStableBatch() {
        Fixture fixture = new Fixture();
        List<RemoteItem> items = List.of(new RemoteItem("file-1", "album/a.mp4", 7, "time"));
        WatchBatch observing = new WatchBatch(fixture.batchId, fixture.accountId, "album",
            fixture.signature(items), fixture.now.minusSeconds(121), "OBSERVING", null, null, 1);
        WatchBatch ready = new WatchBatch(fixture.batchId, fixture.accountId, "album",
            observing.signature(), observing.stableSince(), "READY", null, null, 2);
        when(fixture.watches.baselinedPaths(fixture.accountId)).thenReturn(Set.of());
        when(fixture.connector.listWatchRoot("pikpak", "watch", Set.of())).thenReturn(items);
        when(fixture.watches.find(fixture.accountId, "album")).thenReturn(Optional.of(observing));
        when(fixture.watches.transition(observing, observing.signature(), observing.stableSince(),
            "READY", null, null)).thenReturn(ready);
        when(fixture.watches.items(fixture.batchId)).thenReturn(items);

        WatchScanView result = fixture.service.scan(fixture.accountId);

        assertThat(result.batches()).hasSize(1);
        assertThat(result.batches().getFirst().items().getFirst().storagePath()).isEqualTo("watch/album/a.mp4");
        verify(fixture.watches).replaceItems(fixture.batchId, items);
    }

    /** 目录批次只在下载成功请求后启动异步移动。 */
    @Test
    void shouldStartDirectoryArchive() {
        Fixture fixture = new Fixture();
        WatchBatch ready = new WatchBatch(fixture.batchId, fixture.accountId, "album", "digest",
            fixture.now.minusSeconds(121), "READY", null, null, 2);
        List<RemoteItem> items = List.of(new RemoteItem("file-1", "album/a.mp4", 7, "time"));
        WatchBatch moving = new WatchBatch(fixture.batchId, fixture.accountId, "album", "digest",
            ready.stableSince(), "MOVING", 91L, null, 3);
        when(fixture.watches.requireBatch(fixture.batchId)).thenReturn(ready);
        when(fixture.watches.items(fixture.batchId)).thenReturn(items);
        when(fixture.connector.startMove("pikpak", "watch/album", "backup/album")).thenReturn(91L);
        when(fixture.watches.transition(ready, "digest", ready.stableSince(), "MOVING", 91L, null))
            .thenReturn(moving);

        WatchBatchView result = fixture.service.archive(fixture.batchId);

        assertThat(result.phase()).isEqualTo("MOVING");
        verify(fixture.connector).startMove("pikpak", "watch/album", "backup/album");
    }

    private static final class Fixture {
        private final UUID accountId = UUID.randomUUID();
        private final UUID batchId = UUID.randomUUID();
        private final Instant now = Instant.parse("2026-08-26T10:00:00Z");
        private final PikPakRepository accounts = mock(PikPakRepository.class);
        private final PikPakWatchRepository watches = mock(PikPakWatchRepository.class);
        private final RclonePikPakClient connector = mock(RclonePikPakClient.class);
        private final Account account = new Account(accountId, "main", UUID.randomUUID(), "secret://main",
            "pikpak", "offline", "ready", true, 120);
        private final Watcher watcher = new Watcher(accountId, "watch", "backup", true, 120, true, true);
        private final PikPakWatchService service;

        private Fixture() {
            when(accounts.requireAccount(accountId)).thenReturn(account);
            when(watches.requireWatcher(accountId)).thenReturn(watcher);
            service = new PikPakWatchService(accounts, watches, connector, true,
                Clock.fixed(now, ZoneOffset.UTC));
        }

        private String signature(List<RemoteItem> items) {
            try {
                var method = PikPakWatchService.class.getDeclaredMethod("signature", List.class);
                method.setAccessible(true);
                return (String) method.invoke(service, items);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
