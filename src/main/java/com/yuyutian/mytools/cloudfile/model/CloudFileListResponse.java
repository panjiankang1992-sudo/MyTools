package com.yuyutian.mytools.cloudfile.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudFileListResponse {
    private String path;
    private List<CloudFileItem> items;
}
