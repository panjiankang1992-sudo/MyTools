package com.yuyutian.mytools.identity.controller;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
/** Identity API 错误映射。 */
@RestControllerAdvice
public class IdentityExceptionHandler {
 /** 映射认证失败。 @param exception 异常 @return 错误 */ @ExceptionHandler(SecurityException.class) public ResponseEntity<Map<String,String>> unauthorized(SecurityException exception){return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code","IDENTITY_001","message","Authentication failed"));}
 /** 映射无效请求。 @param exception 异常 @return 错误 */ @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,MethodArgumentNotValidException.class}) public ResponseEntity<Map<String,String>> invalid(Exception exception){return ResponseEntity.badRequest().body(Map.of("code","IDENTITY_002","message","Invalid request"));}
}
