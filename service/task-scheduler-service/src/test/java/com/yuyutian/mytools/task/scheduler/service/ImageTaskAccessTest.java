package com.yuyutian.mytools.task.scheduler.service;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import static org.junit.jupiter.api.Assertions.*;
class ImageTaskAccessTest {
 @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
 @Test void rejectsMissingAndWrongService(){assertThrows(SchedulerException.class,()->ImageTaskAccess.require("image_generate","image:test"));var request=new MockHttpServletRequest();request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE,"reader-service");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));assertThrows(SchedulerException.class,()->ImageTaskAccess.require("IMAGE_GENERATE","other"));assertThrows(SchedulerException.class,()->ImageTaskAccess.require("other","IMAGE:test"));}
 @Test void permitsOnlyImageClientAndDoesNotChangeUnrelatedTasks(){ImageTaskAccess.require("media_generate_tags","media:test");var request=new MockHttpServletRequest();request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE,"image-generation-service");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));assertDoesNotThrow(()->ImageTaskAccess.require("image_generate","image:test"));}
}
