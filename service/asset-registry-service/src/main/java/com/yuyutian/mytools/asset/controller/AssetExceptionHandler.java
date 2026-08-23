package com.yuyutian.mytools.asset.controller;

import com.yuyutian.mytools.asset.model.ErrorCode;
import com.yuyutian.mytools.asset.service.ArtifactCycleException;
import com.yuyutian.mytools.asset.service.AssetNotFoundException;
import com.yuyutian.mytools.asset.service.AssetVersionConflictException;
import com.yuyutian.mytools.asset.service.BundleManifestConflictException;
import com.yuyutian.mytools.asset.service.IdempotencyConflictException;
import com.yuyutian.mytools.asset.service.AssetInputInvalidException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 资产注册业务异常转换器。
 */
@RestControllerAdvice
public class AssetExceptionHandler {

    /**
     * 转换资产不存在异常。
     */
    @ExceptionHandler(AssetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(AssetNotFoundException exception) {
        return response(ErrorCode.ASSET_NOT_FOUND);
    }

    /**
     * 转换幂等冲突异常。
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleIdempotencyConflict(IdempotencyConflictException exception) {
        return response(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    /**
     * 转换乐观版本冲突异常。
     */
    @ExceptionHandler(AssetVersionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleVersionConflict(AssetVersionConflictException exception) {
        return response(ErrorCode.VERSION_CONFLICT);
    }

    /**
     * 转换派生循环异常。
     */
    @ExceptionHandler(ArtifactCycleException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleArtifactCycle(ArtifactCycleException exception) {
        return response(ErrorCode.ARTIFACT_CYCLE);
    }

    /**
     * 转换资产输入无效异常。
     */
    @ExceptionHandler(AssetInputInvalidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleInputInvalid(AssetInputInvalidException exception) {
        return response(ErrorCode.INPUT_INVALID);
    }

    /**
     * 转换资源包清单冲突异常。
     */
    @ExceptionHandler(BundleManifestConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleBundleManifestConflict(BundleManifestConflictException exception) {
        return response(ErrorCode.BUNDLE_MANIFEST_CONFLICT);
    }

    private Map<String, String> response(ErrorCode errorCode) {
        return Map.of("code", errorCode.code(), "message", errorCode.message());
    }
}
