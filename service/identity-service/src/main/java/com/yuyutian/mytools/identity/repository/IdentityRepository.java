package com.yuyutian.mytools.identity.repository;
import com.yuyutian.mytools.identity.model.IdentityModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
/** Identity 数据仓储。 */
@Repository
public class IdentityRepository {
 private final JdbcTemplate jdbc;
 /** 创建仓储。 @param jdbc JDBC 模板 */ public IdentityRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 /** 幂等导入用户和角色。 @param request 请求 @return 用户 */
 public UserView importUser(ImportUserRequest request) {
  List<UserView> existing=findUser(request.id());
  if(!existing.isEmpty()) {
   UserView user=existing.getFirst();
   if(!user.externalUserId().equals(request.externalUserId())||!user.username().equals(request.username())
      ||!Objects.equals(user.email(),request.email())||user.credentialVersion()!=request.credentialVersion()
      ||!user.status().equals(request.status())||!new java.util.TreeSet<>(user.roles()).equals(new java.util.TreeSet<>(request.roles()))
      ||!passwordHash(user.id()).equals(request.passwordHash()))
    throw new IllegalStateException("identity user idempotency conflict");
   return user;
  }
  Instant now=Instant.now();
  jdbc.update("INSERT INTO identity_user VALUES (?,?,?,?,?,?,?,?,?)",request.id(),request.externalUserId(),request.username(),
   request.email(),request.passwordHash(),request.status(),request.credentialVersion(),Timestamp.from(now),Timestamp.from(now));
  for(String role:request.roles()) {
   String roleId=jdbc.query("SELECT id FROM identity_role WHERE name=?",(rs,row)->rs.getString(1),role).stream().findFirst().orElse(null);
   if(roleId==null){roleId=UUID.randomUUID().toString();jdbc.update("INSERT INTO identity_role VALUES (?,?,?,?)",roleId,role,null,Timestamp.from(now));}
   jdbc.update("INSERT INTO identity_user_role VALUES (?,?,?)",request.id(),roleId,Timestamp.from(now));
  }
  return findUser(request.id()).getFirst();
 }
 /** 按用户名查询用户。 @param username 用户名 @return 用户 */ public Optional<UserView> findByUsername(String username){return queryUsers("WHERE u.username=?",username).stream().findFirst();}
 /** 按标识查询用户。 @param id 标识 @return 用户 */ public Optional<UserView> findById(long id){return findUser(id).stream().findFirst();}
 /** 读取密码哈希。 @param userId 用户 @return 哈希 */ public String passwordHash(long userId){return jdbc.queryForObject("SELECT password_hash FROM identity_user WHERE id=?",String.class,userId);}
 /** 创建会话。 @param record 会话 */ public void createSession(SessionRecord record){jdbc.update("INSERT INTO identity_session VALUES (?,?,?,?,?,?,?,?,?,?,?)",
   record.id().toString(),record.userId(),record.deviceId(),record.refreshHash(),record.version(),record.credentialVersion(),
   Timestamp.from(record.issuedAt()),Timestamp.from(record.refreshExpiresAt()),null,null,Timestamp.from(record.issuedAt()));}
 /** 按刷新摘要查询会话。 @param hash 摘要 @return 会话 */ public Optional<SessionRecord> sessionByRefresh(String hash){return session("refresh_token_sha256",hash);}
 /** 按标识查询会话。 @param id 标识 @return 会话 */ public Optional<SessionRecord> session(UUID id){return session("id",id.toString());}
 /** 原子轮换刷新摘要。 @param id 会话 @param oldHash 旧摘要 @param newHash 新摘要 @param version 新版本 @param now 时间 @return 是否成功 */
 public boolean rotate(UUID id,String oldHash,String newHash,long version,Instant now){return jdbc.update("UPDATE identity_session SET refresh_token_sha256=?,version=?,last_seen_at=? WHERE id=? AND refresh_token_sha256=? AND revoked_at IS NULL",
   newHash,version,Timestamp.from(now),id.toString(),oldHash)==1;}
 /** 撤销会话。 @param id 会话 @param reason 原因 */ public void revoke(UUID id,String reason){jdbc.update("UPDATE identity_session SET revoked_at=COALESCE(revoked_at,?),revoke_reason=COALESCE(revoke_reason,?) WHERE id=?",Timestamp.from(Instant.now()),reason,id.toString());}
 /** 查询用户会话。 @param userId 用户 @return 会话 */ public List<SessionView> sessions(long userId){return jdbc.query("SELECT id,device_id,issued_at,refresh_expires_at,revoked_at,last_seen_at FROM identity_session WHERE user_id=? ORDER BY issued_at DESC",(rs,row)->{Instant now=Instant.now();Instant expires=rs.getTimestamp("refresh_expires_at").toInstant();String status=rs.getTimestamp("revoked_at")!=null?"INVALID":(expires.isAfter(now)?"ACTIVE":"EXPIRED");return new SessionView(UUID.fromString(rs.getString("id")),rs.getString("device_id"),status,rs.getTimestamp("issued_at").toInstant(),expires,rs.getTimestamp("last_seen_at").toInstant());},userId);}
 /** 查询登录锁定截止时间。 @param keyHash 身份摘要 @return 截止时间 */ public Optional<Instant> lockedUntil(String keyHash){return jdbc.query("SELECT locked_until FROM identity_login_attempt WHERE identity_key_sha256=?",(rs,row)->rs.getTimestamp(1)==null?null:rs.getTimestamp(1).toInstant(),keyHash).stream().filter(Objects::nonNull).findFirst();}
 /** 记录登录失败并在阈值后锁定。 @param keyHash 身份摘要 @param now 时间 */ public void recordFailure(String keyHash,Instant now){
  Integer failures=jdbc.query("SELECT failure_count FROM identity_login_attempt WHERE identity_key_sha256=?",(rs,row)->rs.getInt(1),keyHash).stream().findFirst().orElse(null);
  int next=failures==null?1:failures+1; Timestamp locked=next>=5?Timestamp.from(now.plusSeconds(900)):null;
  if(failures==null)jdbc.update("INSERT INTO identity_login_attempt VALUES (?,?,?,?,?)",keyHash,next,locked,Timestamp.from(now),Timestamp.from(now));
  else jdbc.update("UPDATE identity_login_attempt SET failure_count=?,locked_until=?,last_failure_at=?,updated_at=? WHERE identity_key_sha256=?",next,locked,Timestamp.from(now),Timestamp.from(now),keyHash);
 }
 /** 清除登录失败状态。 @param keyHash 身份摘要 */ public void clearFailures(String keyHash){jdbc.update("DELETE FROM identity_login_attempt WHERE identity_key_sha256=?",keyHash);}
 /** 查询用户迁移审计。 @param migrationKey 迁移键 @param userId 旧用户标识 @return 迁移审计 */
 public Optional<UserMigrationRecord> findUserMigration(String migrationKey,long userId){return jdbc.query("SELECT * FROM identity_user_migration WHERE migration_key=? AND legacy_user_id=?",(rs,row)->new UserMigrationRecord(rs.getString("migration_key"),rs.getLong("legacy_user_id"),rs.getString("payload_sha256")),migrationKey,userId).stream().findFirst();}
 /** 写入用户迁移审计。 @param migrationKey 迁移键 @param userId 旧用户标识 @param payloadSha256 载荷摘要 */
 public void recordUserMigration(String migrationKey,long userId,String payloadSha256){jdbc.update("INSERT INTO identity_user_migration(migration_key,legacy_user_id,payload_sha256,created_at) VALUES (?,?,?,?)",migrationKey,userId,payloadSha256,Timestamp.from(Instant.now()));}
 /** 按稳定顺序读取迁移审计。 @param migrationKey 迁移键 @return 迁移审计 */
 public List<UserMigrationRecord> findUserMigrations(String migrationKey){return jdbc.query("SELECT * FROM identity_user_migration WHERE migration_key=? ORDER BY legacy_user_id",(rs,row)->new UserMigrationRecord(rs.getString("migration_key"),rs.getLong("legacy_user_id"),rs.getString("payload_sha256")),migrationKey);}
 private Optional<SessionRecord> session(String field,String value){return jdbc.query("SELECT * FROM identity_session WHERE "+field+"=?",(rs,row)->new SessionRecord(UUID.fromString(rs.getString("id")),rs.getLong("user_id"),rs.getString("device_id"),rs.getString("refresh_token_sha256"),rs.getLong("version"),rs.getLong("credential_version"),rs.getTimestamp("issued_at").toInstant(),rs.getTimestamp("refresh_expires_at").toInstant(),rs.getTimestamp("revoked_at")==null?null:rs.getTimestamp("revoked_at").toInstant()),value).stream().findFirst();}
 private List<UserView> findUser(long id){return queryUsers("WHERE u.id=?",id);}
 private List<UserView> queryUsers(String where,Object value){
  // 先完整释放用户查询占用的连接，再读取角色；避免并发校验时每个请求嵌套占用两条连接耗尽连接池。
  List<UserView> users=jdbc.query("SELECT u.* FROM identity_user u "+where,(rs,row)->new UserView(rs.getLong("id"),rs.getString("external_user_id"),rs.getString("username"),rs.getString("email"),rs.getString("status"),rs.getLong("credential_version"),List.of()),value);
  return users.stream().map(user->new UserView(user.id(),user.externalUserId(),user.username(),user.email(),user.status(),user.credentialVersion(),roles(user.id()))).toList();
 }
 private List<String> roles(long userId){return jdbc.query("SELECT r.name FROM identity_role r JOIN identity_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.name",(rs,row)->rs.getString(1),userId);}
 /** 用户迁移审计记录。 @param migrationKey 迁移键 @param userId 旧用户标识 @param payloadSha256 载荷摘要 */
 public record UserMigrationRecord(String migrationKey,long userId,String payloadSha256) { }
}
