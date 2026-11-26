package com.yuyutian.mytools.toolbox.controller.tools;

import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.model.ToolsResponse;
import com.yuyutian.mytools.toolbox.service.tools.ToolsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具注册与能力接口。
 */
@RestController
@RequestMapping("/public/v1/tools")
public class ToolsController {

    private final ToolsService toolsService;

    /**
     * 构造控制器。
     *
     * @param toolsService 工具与能力查询服务
     */
    public ToolsController(ToolsService toolsService) {
        this.toolsService = toolsService;
    }

    /**
     * 返回工具注册表、执行位置、能力、限额与策略版本，不要求会话。
     *
     * @return 工具注册响应信封
     */
    @GetMapping
    public ApiResponse<ToolsResponse> tools() {
        return ApiResponse.ok(toolsService.describe());
    }
}
