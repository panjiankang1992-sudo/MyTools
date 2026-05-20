package com.yuyutian.mytools.webdav.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class WebdavAccountResponse {
    private Long id;
    private Long userId;
    private String type;
    private String url;
    private String username;
    private Boolean passwordSet;
}
