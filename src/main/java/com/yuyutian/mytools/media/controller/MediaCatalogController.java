package com.yuyutian.mytools.media.controller;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.localfile.service.LocalFileService;
import com.yuyutian.mytools.localfile.service.LocalMediaTicketService;
import com.yuyutian.mytools.media.model.MediaCatalogModels.CatalogItem;
import com.yuyutian.mytools.media.model.MediaCatalogModels.FilterResponse;
import com.yuyutian.mytools.media.model.MediaCatalogModels.GalleryResponse;
import com.yuyutian.mytools.media.model.MediaCatalogModels.VideoDetail;
import com.yuyutian.mytools.media.model.MediaCatalogModels.VideoDirectoryResponse;
import com.yuyutian.mytools.media.service.catalog.MediaCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/**
 * App 图库、视频目录和视频详情接口。
 */
@RestController
@RequestMapping("/api/app/v1")
@RequiredArgsConstructor
public class MediaCatalogController {

    private final MediaCatalogService catalogService;
    private final LocalFileService localFileService;
    private final LocalMediaTicketService ticketService;
    private final TokenMapper tokenMapper;

    /**
     * 查询指定媒体页面的 Top 500 目录与标签。
     *
     * @param keyword 模糊搜索词
     * @param mode 页面模式，gallery 或 video
     * @param excludeAdult 是否过滤成人内容
     * @return 页面独立的目录与标签
     */
    @GetMapping("/media/filters")
    public Result<FilterResponse> filters(@RequestParam(defaultValue = "") String keyword,
                                          @RequestParam(defaultValue = "gallery") String mode,
                                          @RequestParam(defaultValue = "false") boolean excludeAdult) {
        return Result.success(catalogService.filters(keyword, mode, excludeAdult));
    }

    /** 查询包含全部媒体类型的图库分页。 */
    @GetMapping("/media/gallery")
    public Result<GalleryResponse> gallery(
            @RequestParam(defaultValue = "") String directoryId,
            @RequestParam(defaultValue = "") String tag,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int pageSize,
            @RequestParam(defaultValue = "false") boolean excludeAdult) {
        return Result.success(catalogService.gallery(directoryId, tag, keyword, page, pageSize, excludeAdult));
    }

    /** 查询按更新时间倒序排列的视频目录。 */
    @GetMapping("/videos/directories")
    public Result<VideoDirectoryResponse> videoDirectories(
            @RequestParam(defaultValue = "") String directoryId,
            @RequestParam(defaultValue = "") String tag,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "false") boolean excludeAdult) {
        return Result.success(catalogService.videoDirectories(directoryId, tag, keyword, excludeAdult));
    }

    /** 查询视频目录内全部文件。 */
    @GetMapping("/videos/directories/{directoryId}/items")
    public Result<List<CatalogItem>> videoDirectoryItems(@PathVariable String directoryId,
                                                         @RequestParam(defaultValue = "false") boolean excludeAdult) {
        return Result.success(catalogService.videoDirectoryItems(directoryId, excludeAdult));
    }

    /** 查询视频详情与截图清单。 */
    @GetMapping("/videos/{videoId}")
    public Result<VideoDetail> videoDetail(@PathVariable Long videoId) {
        return Result.success(catalogService.videoDetail(videoId));
    }

    /**
     * 为视频详情页签发沿用现有本地 Range 播放链路的票据。
     *
     * @param videoId 视频ID
     * @param authorization 访问令牌
     * @return 播放票据
     */
    @PostMapping("/videos/{videoId}/play-ticket")
    public Result<LocalMediaTicketService.TicketResult> playTicket(
            @PathVariable Long videoId,
            @RequestHeader("Authorization") String authorization) {
        // 先确认该文件属于多媒体视频，禁止借此签发任意本地文件票据。
        catalogService.videoDetail(videoId);
        String accessToken = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        Token session = tokenMapper.findByAccessToken(accessToken);
        if (session == null || !"ACTIVE".equals(session.getStatus()) || session.getExpireTime() == null
                || session.getExpireTime() <= System.currentTimeMillis()) {
            throw new BusinessException(ErrorCode.AUTH_002);
        }
        localFileService.getReadableFilePath(videoId);
        return Result.success(ticketService.issue(session.getUserId(), session.getId(), videoId));
    }

    /**
     * 读取详情页中的一张已校验截图。
     *
     * @param videoId 视频ID
     * @param sequence 截图序号
     * @return JPEG截图
     */
    @GetMapping("/videos/{videoId}/storyboard/{sequence}")
    public ResponseEntity<FileSystemResource> storyboard(@PathVariable Long videoId,
                                                          @PathVariable int sequence) {
        Path path = catalogService.storyboardPath(videoId, sequence);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore()).body(new FileSystemResource(path));
    }
}
