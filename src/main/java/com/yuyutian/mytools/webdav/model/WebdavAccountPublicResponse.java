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

    private Boolean passwordSet;
}
