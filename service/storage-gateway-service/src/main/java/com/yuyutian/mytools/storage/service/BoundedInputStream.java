package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 对远端响应实施真实读取字节上限。
 */
final class BoundedInputStream extends FilterInputStream {
    private long remaining;

    /**
     * 创建有界输入流。
     *
     * @param inputStream 原始流
     * @param maximumBytes 最大可读取字节数
     */
    BoundedInputStream(InputStream inputStream, long maximumBytes) {
        super(inputStream);
        this.remaining = maximumBytes;
    }

    /** {@inheritDoc} */
    @Override
    public int read() throws IOException {
        if (remaining == 0) {
            requireEnd();
            return -1;
        }
        int value = super.read();
        if (value >= 0) {
            remaining--;
        }
        return value;
    }

    /** {@inheritDoc} */
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (remaining == 0) {
            requireEnd();
            return -1;
        }
        int read = super.read(bytes, offset, (int) Math.min(length, remaining));
        if (read > 0) {
            remaining -= read;
        }
        return read;
    }

    private void requireEnd() throws IOException {
        if (super.read() >= 0) {
            throw new IOException(ErrorCode.REMOTE_CONTENT_TOO_LARGE.code());
        }
    }
}
