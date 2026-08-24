package com.yuyutian.mytools.user.migration;
import com.yuyutian.mytools.user.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
/** 旧用户身份迁移只读接口。 */
@RestController @RequestMapping("/internal/v1/migration/identity-users")
public class LegacyIdentityExportController {
 private final UserMapper mapper; private final String token;
 /** 创建接口。 @param mapper 用户 Mapper @param token 内部令牌 */ public LegacyIdentityExportController(UserMapper mapper,@Value("${migration.identity-users.internal-token:}") String token){this.mapper=mapper;this.token=token;}
 /** 分页导出身份最小字段。 @param authorization 授权 @param afterId 游标 @param limit 大小 @param snapshotHighWater 冻结高水位 @return 页面 */
 @GetMapping public ExportPage export(@RequestHeader("Authorization") String authorization,@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="100") int limit,@RequestParam(required=false) Long snapshotHighWater){authorize(authorization);if(afterId<0||limit<1||limit>500)throw new IllegalArgumentException("identity migration page is invalid");
  long highWater=snapshotHighWater==null?mapper.selectIdentityMigrationHighWater():snapshotHighWater;
  if(highWater<0||afterId>highWater)throw new IllegalArgumentException("identity migration high water is invalid");
  List<ExportUser> users=mapper.selectFrozenIdentityMigrationBatch(afterId,highWater,limit).stream().map(user->{String role=user.getRole()==null||!user.getRole().matches("^[A-Z][A-Z0-9_]{0,63}$")?"USER":user.getRole();return new ExportUser(user.getId(),"mytools:"+user.getId(),user.getUsername(),user.getEmail(),user.getPassword(),user.getStatus(),0,List.of(role));}).toList();
  return new ExportPage(users,users.isEmpty()?afterId:users.getLast().id(),users.size()<limit,highWater);
 }
 private void authorize(String authorization){byte[] expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8);if(token.isBlank()||!MessageDigest.isEqual(expected,authorization.getBytes(StandardCharsets.UTF_8)))throw new SecurityException("identity migration authorization failed");}
 /** 身份迁移用户。 */ public record ExportUser(long id,String externalUserId,String username,String email,String passwordHash,String status,long credentialVersion,List<String> roles) { }
 /** 身份迁移页面。 */ public record ExportPage(List<ExportUser> users,long nextAfterId,boolean complete,long snapshotHighWater) { }
}
