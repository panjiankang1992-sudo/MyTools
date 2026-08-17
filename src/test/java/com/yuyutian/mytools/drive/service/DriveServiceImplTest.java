package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.drive.infrastructure.rclone.RcloneGateway;
import com.yuyutian.mytools.drive.infrastructure.rclone.RcloneItem;
import com.yuyutian.mytools.drive.mapper.DriveAccountMapper;
import com.yuyutian.mytools.drive.mapper.DriveItemIndexMapper;
import com.yuyutian.mytools.drive.model.DriveAccount;
import com.yuyutian.mytools.drive.model.DriveDirectoryView;
import com.yuyutian.mytools.drive.model.DriveItemIndex;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class DriveServiceImplTest {

    private DriveAccountMapper accountMapper;
    private DriveItemIndexMapper itemMapper;
    private RcloneGateway gateway;
    private DriveServiceImpl service;

    @BeforeEach
    void setUp() {
        accountMapper = mock(DriveAccountMapper.class);
        itemMapper = mock(DriveItemIndexMapper.class);
        gateway = mock(RcloneGateway.class);
        service = new DriveServiceImpl(accountMapper, itemMapper, gateway,
                new SnowflakeIdGenerator(1, 1));
    }

    @Test
    void shouldListDirectoryWithOpaqueItemsAndFoldersFirst() {
        DriveAccount account = account(8L, 5L);
        when(accountMapper.selectOwned(8L, 5L)).thenReturn(account);
        when(gateway.list("family", "")).thenReturn(List.of(
                new RcloneItem("movie.mp4", "movie.mp4", 200L, "video/mp4",
                        OffsetDateTime.parse("2026-08-15T00:00:00Z"), false, "video-id"),
                new RcloneItem("Photos", "Photos", 0L, "inode/directory",
                        OffsetDateTime.parse("2026-08-14T00:00:00Z"), true, "folder-id")));
        DriveDirectoryView result = service.listItems(5L, 8L, null, "", "modified", "desc");

        assertThat(result.itemCount()).isEqualTo(2L);
        assertThat(result.totalSizeBytes()).isEqualTo(200L);
        assertThat(result.items()).extracting(item -> item.kind()).containsExactly("DIRECTORY", "VIDEO");
        assertThat(result.items()).allMatch(item -> item.itemId().matches("[0-9]+"));
        verify(itemMapper, times(2)).insert(any(DriveItemIndex.class));
    }

    @Test
    void shouldRejectCrossUserDriveBeforeCallingGateway() {
        when(accountMapper.selectOwned(8L, 5L)).thenReturn(null);

        assertThatThrownBy(() -> service.listItems(5L, 8L, null, "", "modified", "desc"))
                .isInstanceOf(BusinessException.class);

        verify(gateway, never()).list(any(), any());
    }

    @Test
    void shouldResolveOpenTargetOnlyAfterOwnershipCheck() {
        DriveAccount account = account(8L, 5L);
        DriveItemIndex item = new DriveItemIndex();
        item.setId(12L);
        item.setDriveId(8L);
        item.setRemotePath("movies/a.mp4");
        item.setDisplayName("a.mp4");
        item.setMimeType("video/mp4");
        item.setDirectory(false);
        item.setSizeBytes(123L);
        when(accountMapper.selectOwned(8L, 5L)).thenReturn(account);
        when(itemMapper.selectById(12L, 8L)).thenReturn(item);

        var target = service.resolveOpenTarget(5L, 8L, 12L);

        assertThat(target.remoteKey()).isEqualTo("family");
        assertThat(target.remotePath()).isEqualTo("movies/a.mp4");
        assertThat(target.userId()).isEqualTo(5L);
    }

    private DriveAccount account(Long id, Long userId) {
        DriveAccount account = new DriveAccount();
        account.setId(id);
        account.setUserId(userId);
        account.setDisplayName("Family Drive");
        account.setRemoteKey("family");
        account.setEnabled(true);
        account.setReadOnly(true);
        account.setStatus("ACTIVE");
        return account;
    }
}
