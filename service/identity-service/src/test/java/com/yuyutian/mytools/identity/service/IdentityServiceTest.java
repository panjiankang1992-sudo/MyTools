package com.yuyutian.mytools.identity.service;
import com.yuyutian.mytools.identity.model.IdentityModels.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
/** Identity 会话生命周期测试。 */
@SpringBootTest
class IdentityServiceTest {
 @Autowired private IdentityService service; @Autowired private BCryptPasswordEncoder encoder;
 @Test void shouldRotateRefreshTokenAndRevokeAccessImmediately(){
  service.importUser(new ImportUserRequest(7,"legacy:7","alice","alice@example.com",encoder.encode("correct-password"),"ACTIVE",0,List.of("USER")));
  TokenPair login=service.login(new LoginRequest("alice","correct-password","device-1"));
  assertThat(service.validate(new ValidateRequest(login.accessToken())).active()).isTrue();
  TokenPair refreshed=service.refresh(new RefreshRequest(login.refreshToken()));
  assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
  assertThatThrownBy(()->service.refresh(new RefreshRequest(login.refreshToken()))).isInstanceOf(SecurityException.class);
  service.revoke(login.sessionId(),"USER_LOGOUT");
  assertThat(service.validate(new ValidateRequest(refreshed.accessToken())).active()).isFalse();
  assertThatThrownBy(()->service.refresh(new RefreshRequest(refreshed.refreshToken()))).isInstanceOf(SecurityException.class);
 }
 @Test void shouldLockIdentityAfterFiveFailures(){
  service.importUser(new ImportUserRequest(8,"legacy:8","bob",null,encoder.encode("correct-password"),"ACTIVE",0,List.of("USER")));
  for(int index=0;index<5;index++) assertThatThrownBy(()->service.login(new LoginRequest("bob","wrong","device-2"))).isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->service.login(new LoginRequest("bob","correct-password","device-2"))).isInstanceOf(SecurityException.class).hasMessageContaining("locked");
 }
}
