package com.yuyutian.mytools.drive.controller;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Drive API 异常映射。 */
@RestControllerAdvice
public class DriveExceptionHandler {
    /** 映射未授权异常。 @param exception 异常 @return 错误 */
    @ExceptionHandler(SecurityException.class) public ResponseEntity<Map<String,String>> unauthorized(SecurityException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code","DRIVE_001","message","Unauthorized"));
    }
    /** 映射输入和状态冲突。 @param exception 异常 @return 错误 */
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception exception) {
        HttpStatus status=exception instanceof IllegalStateException?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("code",status==HttpStatus.CONFLICT?"DRIVE_003":"DRIVE_002","message",status.getReasonPhrase()));
    }
}
