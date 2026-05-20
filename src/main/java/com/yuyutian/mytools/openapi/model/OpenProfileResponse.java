package com.yuyutian.mytools.openapi.model;

import com.yuyutian.mytools.user.Model.UserInfoResponse;
import com.yuyutian.mytools.webdav.model.WebdavAccountPublicResponse;
import lombok.Data;

@Data
public class OpenProfileResponse {

    private UserInfoResponse user;

    private WebdavAccountPublicResponse webdav;
}
