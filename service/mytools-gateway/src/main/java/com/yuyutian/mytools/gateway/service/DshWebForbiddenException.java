package com.yuyutian.mytools.gateway.service;

/** 已登录账号没有 DSH 工作区权限，区别于登录失效。 */
public class DshWebForbiddenException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
