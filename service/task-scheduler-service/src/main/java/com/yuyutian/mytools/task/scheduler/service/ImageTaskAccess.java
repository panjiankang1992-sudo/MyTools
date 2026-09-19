package com.yuyutian.mytools.task.scheduler.service;
import com.yuyutian.mytools.task.scheduler.common.*;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.*;
/** 图片执行只能由独立图片服务凭据触发和读取。 */
public final class ImageTaskAccess {
 private ImageTaskAccess() { }
 /** 保护图片任务名称和幂等键，拒绝共享认证或其他业务身份。 */
 public static void require(String name,String key){
  if(!"image_generate".equalsIgnoreCase(name)&&(key==null||!key.toLowerCase(java.util.Locale.ROOT).startsWith("image:")))return;
  var attributes=RequestContextHolder.getRequestAttributes();
  if(!(attributes instanceof ServletRequestAttributes servlet)||!"image-generation-service".equals(servlet.getRequest().getAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE)))
   throw new SchedulerException(ErrorCode.UNAUTHORIZED,HttpStatus.UNAUTHORIZED,"Image service identity is required");
 }
}
