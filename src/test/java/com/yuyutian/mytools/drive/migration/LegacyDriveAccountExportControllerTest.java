package com.yuyutian.mytools.drive.migration;

import com.yuyutian.mytools.drive.mapper.DriveAccountMapper;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import com.yuyutian.mytools.webdav.model.WebdavAccount;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 旧账户安全导出测试。 */
class LegacyDriveAccountExportControllerTest {
    @Test
    void shouldExportOnlySecretReferenceForWebdavAccount() {
        DriveAccountMapper driveMapper=mock(DriveAccountMapper.class);
        WebdavAccountMapper webdavMapper=mock(WebdavAccountMapper.class);
        WebdavAccount account=new WebdavAccount(); account.setId(12L); account.setUserId(7L);
        account.setName("Cloud"); account.setType("alist"); account.setPassword("encrypted-secret");
        when(webdavMapper.selectMigrationHighWater()).thenReturn(12L);
        when(webdavMapper.selectMigrationBatch(0L,12L,100)).thenReturn(List.of(account));
        LegacyDriveAccountExportController controller=new LegacyDriveAccountExportController(
            driveMapper,webdavMapper,"token");

        var page=controller.export("Bearer token","WEBDAV",0,null,100);

        assertThat(page.accounts()).singleElement().satisfies(exported -> {
            assertThat(exported.providerSecretRef()).isEqualTo("secret://mytools/webdav/12");
            assertThat(exported.enabled()).isFalse();
        });
        assertThat(page.toString()).doesNotContain("encrypted-secret");
        assertThat(page.snapshotHighWater()).isEqualTo(12L);
        assertThat(page.complete()).isTrue();
    }
}
