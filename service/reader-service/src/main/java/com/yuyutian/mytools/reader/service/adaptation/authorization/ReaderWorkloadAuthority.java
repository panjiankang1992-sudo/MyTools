package com.yuyutian.mytools.reader.service.adaptation.authorization;

import com.yuyutian.mytools.reader.model.adaptation.WorkloadIntrospection;

import java.security.PublicKey;
import java.time.Instant;

/** Scheduler 公钥与在线权威状态接口；公钥可缓存，授权状态不能缓存。 */
public interface ReaderWorkloadAuthority {
    /** 仅从可信 Scheduler 公钥集解析指定 key，不跟随 token 提供的 URL。 */
    PublicKey publicKey(String keyId);
    /** 返回本次检查的有效期限，已撤销返回空值，不确定抛出授权不可用异常。 */
    Instant activeUntil(WorkloadIntrospection request);
}
