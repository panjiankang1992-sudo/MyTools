package com.yuyutian.mytools.drive.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.drive.model.DriveAccountView;
import com.yuyutian.mytools.drive.model.DriveDirectoryView;
import com.yuyutian.mytools.drive.service.DriveService;
import com.yuyutian.mytools.drive.service.DriveTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * App 统一网盘查询接口。
 */
@RestController
@RequestMapping("/api/app/v1/drives")
@RequiredArgsConstructor
public class DriveController {

    private final DriveService driveService;
    private final DriveTicketService ticketService;

    /** 查询当前用户可用网盘。 */
    @GetMapping
    public Result<List<DriveAccountView>> list(@RequestAttribute("userId") Long userId) {
        return Result.success(driveService.listDrives(userId));
    }

    /** 浏览目录或对已同步元数据执行模糊搜索。 */
    @GetMapping("/{driveId}/items")
    public Result<DriveDirectoryView> items(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long driveId,
            @RequestParam(required = false) String itemId,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "modified") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return Result.success(driveService.listItems(userId, driveId, itemId, keyword, sort, direction));
    }

    /** 为网盘文件签发短期只读打开票据。 */
    @PostMapping("/{driveId}/items/{itemId}/open-ticket")
    public Result<DriveTicketService.TicketResult> openTicket(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long driveId,
            @PathVariable Long itemId) {
        return Result.success(ticketService.issue(driveService.resolveOpenTarget(userId, driveId, itemId)));
    }
}
