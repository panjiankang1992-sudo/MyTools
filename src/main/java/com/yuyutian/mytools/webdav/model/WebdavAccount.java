package com.yuyutian.mytools.webdav.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WebdavAccount {
    private Long id;
    private Long userId;
    private String type;
    private String url;
    private String username;

    @JsonIgnore
    private String password;

    private Integer isActive;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean passwordSet;
}
