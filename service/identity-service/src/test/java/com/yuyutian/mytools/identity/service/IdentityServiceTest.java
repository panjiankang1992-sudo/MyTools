package com.yuyutian.mytools.identity.service;
import com.yuyutian.mytools.identity.model.IdentityModels.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
/** Identity 会话生命周期测试。 */
@SpringBootTest
class IdentityServiceTest {
 @Autowired private IdentityService service; @Autowired private BCryptPasswordEncoder encoder; @Autowired private JdbcTemplate jdbc;
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
 @Test void shouldDryRunApplyReplayAndReconcileLegacyUsers(){
  ImportUserRequest user=new ImportUserRequest(9,"legacy:9","carol","carol@example.com",encoder.encode("correct-password"),"ACTIVE",0,List.of("USER","ADMIN"));
  LegacyUserMigrationResult dryRun=service.migrateUsers(new LegacyUserMigrationBatch("identity-users-test",true,List.of(user)));
  assertThat(dryRun.accepted()).isEqualTo(1);
  assertThat(service.reconcileUsers("identity-users-test").itemCount()).isZero();
  LegacyUserMigrationResult applied=service.migrateUsers(new LegacyUserMigrationBatch("identity-users-test",false,List.of(user)));
  LegacyUserMigrationResult replay=service.migrateUsers(new LegacyUserMigrationBatch("identity-users-test",false,List.of(user)));
  assertThat(applied.digestSha256()).isEqualTo(dryRun.digestSha256());
  assertThat(replay.accepted()).isZero();
  assertThat(replay.skipped()).isEqualTo(1);
  assertThat(service.reconcileUsers("identity-users-test").collectionSha256()).isEqualTo(applied.digestSha256());
  assertThatThrownBy(()->service.migrateUsers(new LegacyUserMigrationBatch("identity-users-test",false,List.of(new ImportUserRequest(9,"legacy:9","carol","carol@example.com",encoder.encode("different"),"ACTIVE",0,List.of("USER","ADMIN")))))).isInstanceOf(IllegalStateException.class);
  jdbc.update("UPDATE identity_user SET email=? WHERE id=?","changed@example.com",9);
  assertThatThrownBy(()->service.reconcileUsers("identity-users-test")).isInstanceOf(IllegalStateException.class).hasMessageContaining("drift");
 }
 @Test void shouldMatchPythonMigrationDigestProtocol(){
  ImportUserRequest user=new ImportUserRequest(10,"mytools:10","fixture","fixture@example.com","$2a$10$"+"x".repeat(53),"ACTIVE",0,List.of("USER","ADMIN"));
  LegacyUserMigrationResult result=service.migrateUsers(new LegacyUserMigrationBatch("identity-users-protocol",true,List.of(user)));
  assertThat(result.digestSha256()).isEqualTo("345a1029ff2e504410b823845302624026cfb86ab74f6857af2f3c571b9b6801");
 }
 @Test void shouldValidateConcurrentRequestsWithBoundedConnectionPool() throws Exception {
  service.importUser(new ImportUserRequest(11,"mytools:11","parallel","parallel@example.com",encoder.encode("correct-password"),"ACTIVE",0,List.of("USER")));
  TokenPair login=service.login(new LoginRequest("parallel","correct-password","device-parallel"));
  ExecutorService executor=Executors.newFixedThreadPool(8);
  CountDownLatch start=new CountDownLatch(1);
  try {
   List<Future<PrincipalView>> results=java.util.stream.IntStream.range(0,32).mapToObj(index->executor.submit(()->{start.await();return service.validate(new ValidateRequest(login.accessToken()));})).toList();
   start.countDown();
   for(Future<PrincipalView> result:results)assertThat(result.get(5,TimeUnit.SECONDS).active()).isTrue();
  } finally {executor.shutdownNow();}
 }
}
