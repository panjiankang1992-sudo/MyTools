package com.yuyutian.mytools.cloudfile.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileOperationResponse {
    private String name;
    private String path;
    private long size;
    private Instant lastModified;
}
