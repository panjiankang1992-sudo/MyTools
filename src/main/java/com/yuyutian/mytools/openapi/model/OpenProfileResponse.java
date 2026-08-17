package com.yuyutian.mytools.openapi.model;

import com.yuyutian.mytools.user.Model.UserInfoResponse;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对外开放的用户资料响应。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OpenProfileResponse extends UserInfoResponse {

    /** WebDAV 服务类型 */
    private String webdavType;

    /** WebDAV 服务器地址 */
    private String webdavUrl;

    /** WebDAV 用户名 */
    private String webdavUsername;

    /** WebDAV 密码是否已设置 */
    private Boolean webdavPasswordSet;
}
