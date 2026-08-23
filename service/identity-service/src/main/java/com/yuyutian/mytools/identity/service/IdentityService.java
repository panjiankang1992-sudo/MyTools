package com.yuyutian.mytools.identity.service;
import com.yuyutian.mytools.identity.config.IdentityProperties;
import com.yuyutian.mytools.identity.model.IdentityModels.*;
import com.yuyutian.mytools.identity.repository.IdentityRepository;
import io.jsonwebtoken.Claims;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
/** Identity 认证与会话服务。 */
@Service
public class IdentityService {
 private final IdentityRepository repository; private final BCryptPasswordEncoder encoder; private final JwtService jwt;
 private final IdentityProperties properties; private final TransactionTemplate transactions; private final SecureRandom random=new SecureRandom();
 /** 创建服务。 @param repository 仓储 @param encoder 密码编码器 @param jwt JWT 服务 @param properties 配置 @param transactions 事务模板 */
 public IdentityService(IdentityRepository repository,BCryptPasswordEncoder encoder,JwtService jwt,IdentityProperties properties,TransactionTemplate transactions){this.repository=repository;this.encoder=encoder;this.jwt=jwt;this.properties=properties;this.transactions=transactions;}
 /** 导入用户。 @param request 请求 @return 用户 */ public UserView importUser(ImportUserRequest request){return transactions.execute(s->repository.importUser(request));}
 /** 登录并创建会话。 @param request 请求 @return Token */ public TokenPair login(LoginRequest request){
  String keyHash=sha256(request.username().toLowerCase(Locale.ROOT));Instant now=Instant.now();
  if(repository.lockedUntil(keyHash).filter(lock->lock.isAfter(now)).isPresent())throw new SecurityException("identity login is locked");
  UserView user=repository.findByUsername(request.username()).orElse(null);
  if(user==null||!"ACTIVE".equals(user.status())||!encoder.matches(request.password(),repository.passwordHash(user.id()))){repository.recordFailure(keyHash,now);throw new SecurityException("identity credentials are invalid");}
  return transactions.execute(s->{repository.clearFailures(keyHash);String refresh=token();SessionRecord session=new SessionRecord(UUID.randomUUID(),user.id(),request.deviceId(),sha256(refresh),1,user.credentialVersion(),now,now.plusSeconds(properties.refreshSeconds()),null);
   repository.createSession(session);return pair(user,session,refresh);});
 }
 /** 轮换刷新令牌。 @param request 请求 @return 新 Token */ public TokenPair refresh(RefreshRequest request){return transactions.execute(s->{Instant now=Instant.now();String oldHash=sha256(request.refreshToken());
  SessionRecord session=repository.sessionByRefresh(oldHash).orElseThrow(()->new SecurityException("identity refresh token is invalid"));
  UserView user=repository.findById(session.userId()).orElseThrow(()->new SecurityException("identity user is unavailable"));
  if(session.revokedAt()!=null||!session.refreshExpiresAt().isAfter(now)||session.credentialVersion()!=user.credentialVersion()||!"ACTIVE".equals(user.status()))throw new SecurityException("identity session is inactive");
  String refresh=token();SessionRecord rotated=new SessionRecord(session.id(),session.userId(),session.deviceId(),sha256(refresh),session.version()+1,session.credentialVersion(),session.issuedAt(),session.refreshExpiresAt(),null);
  if(!repository.rotate(session.id(),oldHash,rotated.refreshHash(),rotated.version(),now))throw new SecurityException("identity refresh token was already rotated");
  return pair(user,rotated,refresh);
 });}
 /** 校验访问令牌及实时会话状态。 @param request 请求 @return 主体 */ public PrincipalView validate(ValidateRequest request){try{Claims claims=jwt.parse(request.accessToken());UUID sessionId=UUID.fromString(claims.get("sid",String.class));long userId=Long.parseLong(claims.getSubject());
  SessionRecord session=repository.session(sessionId).orElseThrow();UserView user=repository.findById(userId).orElseThrow();
  boolean active=session.userId()==userId&&session.revokedAt()==null&&session.credentialVersion()==user.credentialVersion()&&"ACTIVE".equals(user.status());
  return new PrincipalView(active,userId,user.username(),user.roles(),sessionId,claims.getExpiration().toInstant());
 }catch(RuntimeException exception){return new PrincipalView(false,0,"",List.of(),null,null);}}
 /** 实时撤销会话。 @param id 会话 @param reason 原因 */ public void revoke(UUID id,String reason){transactions.executeWithoutResult(s->repository.revoke(id,reason));}
 private TokenPair pair(UserView user,SessionRecord session,String refresh){return new TokenPair(jwt.issue(user,session),refresh,"Bearer",properties.accessSeconds(),Math.max(0,session.refreshExpiresAt().getEpochSecond()-Instant.now().getEpochSecond()),session.id());}
 private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException exception){throw new IllegalStateException(exception);}}
}
