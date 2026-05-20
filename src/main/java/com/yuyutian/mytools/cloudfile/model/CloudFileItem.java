package com.yuyutian.mytools.cloudfile.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudFileItem {

    private String name;
    private String path;
    private boolean isDirectory;
    private long size;

    @JsonProperty("contentType")
    private String contentType;

    private Instant lastModified;
    private String etag;
}
