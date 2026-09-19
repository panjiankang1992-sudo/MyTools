package com.yuyutian.mytools.reader.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 恢复扫描可在读取、新建或派发关闭时独立运行；业务终态仍须保留派发取消确认。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "reader.adaptation-recovery", name = "enabled", havingValue = "true")
public class ReaderAdaptationRecoverySchedulingConfiguration {
}
