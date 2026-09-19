package com.yuyutian.mytools.reader.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 显式启用改编派发和恢复轮询，与章节准备及 App 新建开关分别控制。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "reader.adaptation-dispatch", name = "enabled", havingValue = "true")
public class ReaderAdaptationSchedulingConfiguration {
}
