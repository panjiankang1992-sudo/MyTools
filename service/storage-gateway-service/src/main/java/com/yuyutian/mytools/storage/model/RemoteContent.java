package com.yuyutian.mytools.storage.model;

import java.io.InputStream;

/** 受控远端对象流及可选长度。 */
public record RemoteContent(InputStream stream, long contentLength) { }
