package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.StorageObject;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 受管根内对象安全读取服务。
 */
@Service
public class StorageObjectService {

    private final StorageRepository repository;

    /**
     * 创建对象读取服务。
     *
     * @param repository 存储仓储
     */
    public StorageObjectService(StorageRepository repository) {
        this.repository = repository;
    }

    /**
     * 解析并验证一个可读取的受管对象。
     *
     * @param rootName 受管根名称
     * @param relativePath 根内相对路径
     * @return 已验证对象
     */
    public StorageObject requireReadable(String rootName, String relativePath) {
        var rootRecord = repository.findRoot(rootName)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.ROOT_NOT_FOUND.code()));
        Path root = Path.of(rootRecord.basePath()).toAbsolutePath().normalize();
        String pathValue = relativePath == null ? "" : relativePath;
        if (pathValue.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        Path relative = Path.of(pathValue).normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        Path candidate = root.resolve(relative).normalize();
        try {
            Path realRoot = root.toRealPath();
            Path current = root;
            for (Path segment : relative) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) {
                    throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
                }
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || !candidate.toRealPath().startsWith(realRoot)) {
                throw new IllegalArgumentException(ErrorCode.UPLOAD_NOT_FOUND.code());
            }
            return new StorageObject(candidate, Files.size(candidate));
        } catch (IOException exception) {
            throw new IllegalArgumentException(ErrorCode.UPLOAD_NOT_FOUND.code(), exception);
        }
    }
}
