package com.yuyutian.mytools.storage.service;

import java.util.Map;

/**
 * 将密钥引用解析为短生命周期凭据材料。
 */
public interface SecretMaterialResolver {
    /**
     * 解析密钥引用，调用方不得持久化或记录返回值。
     *
     * @param secretRef 密钥引用
     * @return 凭据字段
     */
    Map<String, String> resolve(String secretRef);
}
