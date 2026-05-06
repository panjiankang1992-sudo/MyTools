package com.yuyutian.mytools.user.Model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新用户状态请求参数。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStatusRequest {

    /** 用户状态：ACTIVE 或 DISABLED */
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(ACTIVE|DISABLED)$", message = "状态必须为ACTIVE或DISABLED")
    private String status;
}