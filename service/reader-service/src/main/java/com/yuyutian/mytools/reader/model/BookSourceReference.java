package com.yuyutian.mytools.reader.model;

import java.util.UUID;

/**
 * 已迁移书源的稳定引用。
 *
 * @param id 书源标识
 * @param sourceUrl 书源地址
 * @param version 当前版本
 */
public record BookSourceReference(UUID id, String sourceUrl, int version) {
}
