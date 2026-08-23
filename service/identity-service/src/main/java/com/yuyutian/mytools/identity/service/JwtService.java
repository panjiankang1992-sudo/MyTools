package com.yuyutian.mytools.identity.service;
import com.yuyutian.mytools.identity.config.IdentityProperties;
import com.yuyutian.mytools.identity.model.IdentityModels.*;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.*;
/** Identity JWT 签发与验证器。 */
@Component
public class JwtService {
 private final IdentityProperties properties; private final SecretKey key;
 /** 创建 JWT 服务。 @param properties 配置 */ public JwtService(IdentityProperties properties){this.properties=properties;
  try{byte[] decoded=Base64.getDecoder().decode(properties.jwtSecret());if(decoded.length<32)throw new IllegalStateException("Identity JWT key must be at least 256 bits");this.key=Keys.hmacShaKeyFor(decoded);}
  catch(IllegalArgumentException exception){throw new IllegalStateException("Identity JWT secret must be Base64",exception);}}
 /** 签发访问令牌。 @param user 用户 @param session 会话 @return 令牌 */ public String issue(UserView user,SessionRecord session){Instant now=Instant.now();return Jwts.builder().issuer(properties.issuer()).subject(Long.toString(user.id())).id(UUID.randomUUID().toString())
   .claim("sid",session.id().toString()).claim("username",user.username()).claim("roles",user.roles()).claim("type","access")
   .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(properties.accessSeconds()))).signWith(key).compact();}
 /** 解析并验证访问令牌。 @param token 令牌 @return Claims */ public Claims parse(String token){Claims claims=Jwts.parser().verifyWith(key).requireIssuer(properties.issuer()).build().parseSignedClaims(token).getPayload();if(!"access".equals(claims.get("type",String.class)))throw new JwtException("token type is invalid");return claims;}
}
