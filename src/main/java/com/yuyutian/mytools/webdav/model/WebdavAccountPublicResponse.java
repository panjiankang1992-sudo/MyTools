package com.yuyutian.mytools.webdav.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class WebdavAccountPublicResponse {

    private Long id;

    private Long userId;

    /** 服务类型：jianguoyun / nextcloud / owncloud / synology / alist / s3 / custom */
    private String type;

    /** WebDAV 服务器地址 */
    private String url;

    /** WebDAV 用户名 */
    private String username;

    /** AES-GCM 加密后的密码密文（Base64编码），可用于客户端解密 */
    private String encryptedPassword;

    private Boolean passwordSet;
}
