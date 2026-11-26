package com.yuyutian.mytools.pikpak.service;

import static com.yuyutian.mytools.pikpak.common.ErrorCode.RCLONE_FAILURE;
import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** 首个账户远端失败时，仍返回另一账户可下载的批次。 */
    @Test
    void shouldReturnHealthyWatcherBatchesAfterRemoteFailure() {
        Fixture fixture = new Fixture();
        Watcher second = fixture.withSecondWatcher();
        List<RemoteItem> items = List.of(new RemoteItem("file-1", "album/a.mp4", 7, "time"));
        WatchBatch ready = new WatchBatch(fixture.batchId, second.accountId(), "album",
            fixture.signature(items), fixture.now.minusSeconds(121), "READY", null, null, 1);
        when(fixture.connector.listWatchRoot("pikpak", "watch", Set.of()))
            .thenThrow(new IllegalStateException(RCLONE_FAILURE.code()));
        when(fixture.connector.listWatchRoot("pikpak-other", "inbox", Set.of())).thenReturn(items);
        when(fixture.watches.find(second.accountId(), "album")).thenReturn(Optional.of(ready));
        when(fixture.watches.items(fixture.batchId)).thenReturn(items);

        List<WatchScanView> result = fixture.service.scanAll();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().accountId()).isEqualTo(second.accountId());
        assertThat(result.getFirst().batches()).extracting(WatchBatchView::id).containsExactly(fixture.batchId);
    }

    /** 所有账户均远端失败时，保留失败并确认已尝试后续账户。 */
    @Test
    void shouldFailWhenEveryWatcherRemoteFails() {
        Fixture fixture = new Fixture();
        fixture.withSecondWatcher();
        IllegalStateException firstFailure = new IllegalStateException(RCLONE_FAILURE.code());
        when(fixture.connector.listWatchRoot("pikpak", "watch", Set.of())).thenThrow(firstFailure);
        when(fixture.connector.listWatchRoot("pikpak-other", "inbox", Set.of()))
            .thenThrow(new IllegalStateException(RCLONE_FAILURE.code()));

        assertThatThrownBy(fixture.service::scanAll).isSameAs(firstFailure);
        verify(fixture.connector).listWatchRoot("pikpak-other", "inbox", Set.of());
    }

    /** 仓储异常不转换为远端失败，避免隐藏本地数据问题。 */
    @Test
    void shouldPropagateUnexpectedWatcherFailure() {
        Fixture fixture = new Fixture();
        fixture.withSecondWatcher();
        IllegalStateException localFailure = new IllegalStateException();
        when(fixture.watches.baselinedPaths(fixture.accountId)).thenThrow(localFailure);

        assertThatThrownBy(fixture.service::scanAll).isSameAs(localFailure);
        verifyNoInteractions(fixture.connector);
    }

    /** 归档中的目录出现部分文件时保持原检查点。 */
    @Test
    void shouldPreserveMovingBatchWhenRemoteSnapshotChanges() {
        Fixture fixture = new Fixture();
        WatchBatch moving = new WatchBatch(fixture.batchId, fixture.accountId, "album", "original",
            fixture.now.minusSeconds(121), "MOVING", 91L, null, 3);
        when(fixture.connector.listWatchRoot("pikpak", "watch", Set.of()))
            .thenReturn(List.of(new RemoteItem("remaining", "album/a.mp4", 7, "time")));
        when(fixture.watches.find(fixture.accountId, "album")).thenReturn(Optional.of(moving));

        assertThat(fixture.service.scan(fixture.accountId).batches()).isEmpty();
        verify(fixture.watches, never()).transition(any(), anyString(), any(), anyString(), any(), any());
        verify(fixture.watches, never()).replaceItems(any(), anyList());
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
            when(watches.lockWatcher(accountId)).thenReturn(watcher);
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
            service = new PikPakWatchService(accounts, watches, connector, new TransactionTemplate(manager), true,
                Clock.fixed(now, ZoneOffset.UTC));
        }

        private Watcher withSecondWatcher() {
            UUID otherId = UUID.randomUUID();
            Account other = new Account(otherId, "other", UUID.randomUUID(), "secret://other",
                "pikpak-other", "offline", "ready", true, 120);
            Watcher second = new Watcher(otherId, "inbox", "backup", true, 120, true, true);
            when(accounts.requireAccount(otherId)).thenReturn(other);
            when(watches.requireWatcher(otherId)).thenReturn(second);
            when(watches.lockWatcher(otherId)).thenReturn(second);
            when(watches.enabledWatchers()).thenReturn(List.of(watcher, second));
            return second;
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
