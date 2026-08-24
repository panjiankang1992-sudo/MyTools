package com.yuyutian.mytools.user.migration;
import com.yuyutian.mytools.user.Model.User;
import com.yuyutian.mytools.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
/** 身份迁移导出测试。 */
class LegacyIdentityExportControllerTest {
 @Test void shouldExportPasswordHashWithoutLegacyTokens(){UserMapper mapper=mock(UserMapper.class);User user=new User();user.setId(7L);user.setUsername("alice");user.setPassword("$2a$10$hash");user.setRole("USER");user.setStatus("ACTIVE");when(mapper.selectIdentityMigrationHighWater()).thenReturn(7L);when(mapper.selectFrozenIdentityMigrationBatch(0L,7L,100)).thenReturn(List.of(user));var page=new LegacyIdentityExportController(mapper,"token").export("Bearer token",0,100,null);assertThat(page.users()).singleElement().satisfies(exported->{assertThat(exported.passwordHash()).isEqualTo("$2a$10$hash");assertThat(exported.roles()).containsExactly("USER");});assertThat(page.snapshotHighWater()).isEqualTo(7L);assertThat(page.toString()).doesNotContain("refreshToken","accessToken");}
}
