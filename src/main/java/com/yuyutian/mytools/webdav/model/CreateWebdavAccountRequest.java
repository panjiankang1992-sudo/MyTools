package com.yuyutian.mytools.webdav.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateWebdavAccountRequest {

    @NotBlank(message = "webdav.type.notBlank")
    @Pattern(regexp = "^(jianguoyun|nextcloud|owncloud|synology|alist|s3|custom)$",
             message = "webdav.type.invalid")
    private String type;

    @NotBlank(message = "webdav.name.notBlank")
    @Size(max = 64, message = "webdav.name.size")
    private String name;

    @NotBlank(message = "webdav.url.notBlank")
    @Size(max = 512, message = "webdav.url.size")
    private String url;

    @NotBlank(message = "webdav.username.notBlank")
    @Size(max = 128, message = "webdav.username.size")
    private String username;

    @NotBlank(message = "webdav.password.notBlank")
    @Size(max = 128, message = "webdav.password.size")
    private String password;

    private Boolean isDefault;
}
