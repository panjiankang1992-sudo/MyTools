package com.yuyutian.mytools.webdav.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateWebdavAccountRequest {

    @NotBlank(message = "webdav.type.notBlank")
    @Pattern(regexp = "^(jianguoyun|nextcloud|owncloud|synology|alist|s3|custom)$",
             message = "webdav.type.invalid")
    private String type;

    @NotBlank(message = "webdav.url.notBlank")
    @Size(max = 512, message = "webdav.url.size")
    private String url;

    @NotBlank(message = "webdav.username.notBlank")
    @Size(max = 128, message = "webdav.username.size")
    private String username;

    @Size(max = 128, message = "webdav.password.size")
    private String password;
}
