package com.yuyutian.mytools.messaging.model;
/** 问题反馈迁移结果。 */ public record LegacyFeedbackMigrationResult(boolean dryRun,int accepted,int skipped,int rejected,String digestSha256){}
