# App Market 应用市场实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现应用市场功能，支持上架/浏览/下载 CLI工具、MCP服务器、Claude Skill 等应用。

**Architecture:**
- 后端：Spring Boot REST API，文件存储在本地目录 `/opt/yuyutian/MyTools/app-market-files/`
- 前端：Vue3 + TypeScript + NaiveUI + Tiptap 富文本编辑器，Elegant Router 自动路由生成
- 数据库：MySQL，3张表（t_app_market, t_app_version, t_app_file）

**Tech Stack:** Java 21 + Spring Boot 3, Vue3 + TypeScript + NaiveUI, MyBatis-Plus, Tiptap, Elegant Router

---

## 一、文件结构

### Backend (Java)
```
src/main/java/com/yuyutian/mytools/
├── appmarket/
│   ├── controller/
│   │   ├── AppMarketController.java      # 应用CRUD API
│   │   └── AppMarketFileController.java   # 文件上传下载 API
│   ├── entity/
│   │   ├── AppMarket.java                # 应用主表实体
│   │   ├── AppVersion.java               # 历史版本表实体
│   │   └── AppFile.java                  # 应用文件表实体
│   ├── dto/
│   │   ├── AppMarketCreateRequest.java   # 上架请求DTO
│   │   ├── AppMarketUpdateRequest.java   # 编辑请求DTO
│   │   ├── AppMarketListRequest.java     # 列表查询DTO
│   │   ├── AppMarketDetailResponse.java  # 详情响应DTO
│   │   └── AppMarketListResponse.java    # 列表响应DTO
│   ├── mapper/
│   │   ├── AppMarketMapper.java
│   │   ├── AppVersionMapper.java
│   │   └── AppFileMapper.java
│   ├── service/
│   │   ├── AppMarketService.java
│   │   └── impl/AppMarketServiceImpl.java
│   └── enums/
│       ├── AppType.java                  # app/cli/mcp/skill 枚举
│       └── AppStatus.java                # PUBLISHED/DRAFT 枚举
├── common/
│   └── ErrorCode.java                    # 新增 APP_xxx 错误码
└── config/
    └── WebConfig.java                     # 新增文件下载路径映射
```

### Frontend (Vue3)
```
webapp/src/
├── service/api/
│   └── appmarket.ts                      # API 函数定义
├── views/app-market/
│   ├── index.vue                         # 列表页
│   └── components/
│       └── AppMarketDrawer.vue           # 侧滑组件（detail/publish 两种模式）
├── locales/langs/zh-cn.ts                 # 新增 i18n
└── router/elegant/routes.ts             # 自动生成，无需手动修改（创建 views/app-market/index.vue 自动触发）
```

### Database Migration
```
sql/migration/V2026_05_16__create_app_market_tables.sql
```

---

## 二、任务分解

### Task 1: 数据库迁移脚本

**Files:**
- Create: `sql/migration/V2026_05_16__create_app_market_tables.sql`

- [ ] **Step 1: 编写数据库迁移脚本**

```sql
-- 应用市场主表
CREATE TABLE t_app_market (
    id VARCHAR(19) NOT NULL PRIMARY KEY COMMENT '主键 Snowflake ID',
    user_id BIGINT NOT NULL COMMENT '发布人用户ID',
    name VARCHAR(100) NOT NULL COMMENT '应用名称',
    type VARCHAR(20) NOT NULL COMMENT 'app/cli/mcp/skill',
    version VARCHAR(50) NOT NULL DEFAULT '1.0.0' COMMENT '当前版本号',
    thumbnail_id VARCHAR(19) DEFAULT NULL COMMENT '缩略图文件ID',
    content TEXT COMMENT '应用简介(富文本HTML)',
    install_cmd VARCHAR(500) DEFAULT NULL COMMENT '安装命令',
    download_url VARCHAR(500) DEFAULT NULL COMMENT '外部下载链接',
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DRAFT',
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_type (type),
    INDEX idx_status (status),
    INDEX idx_name (name),
    INDEX idx_created_time (created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用市场主表';

-- 历史版本表
CREATE TABLE t_app_version (
    id VARCHAR(19) NOT NULL PRIMARY KEY COMMENT '主键',
    app_id VARCHAR(19) NOT NULL COMMENT '所属应用ID',
    version VARCHAR(50) NOT NULL COMMENT '版本号',
    content TEXT COMMENT '该版本的简介',
    file_id VARCHAR(19) DEFAULT NULL COMMENT '该版本的文件ID',
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    INDEX idx_app_id (app_id),
    INDEX idx_version (app_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用历史版本表';

-- 应用文件表
CREATE TABLE t_app_file (
    id VARCHAR(19) NOT NULL PRIMARY KEY COMMENT '主键',
    app_id VARCHAR(19) NOT NULL COMMENT '所属应用ID',
    version_id VARCHAR(19) DEFAULT NULL COMMENT '所属版本ID(可为null表示当前版本)',
    file_type VARCHAR(20) NOT NULL COMMENT 'thumbnail/binary/json/zip/html',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_path VARCHAR(500) NOT NULL COMMENT '存储路径',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    INDEX idx_app_id (app_id),
    INDEX idx_version_id (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用文件表';
```

- [ ] **Step 2: 提交**

```bash
git add sql/migration/V2026_05_16__create_app_market_tables.sql
git commit -m "feat: add app market database migration"
```

---

### Task 2: 后端 - 枚举和错误码

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/common/ErrorCode.java`（添加 APP_xxx 错误码）

- [ ] **Step 1: 添加错误码**

在 `ErrorCode.java` 的 `// Token error codes` 段落后添加：

```java
// App Market error codes (70001-70099)
APP_001("70001", "app.market.app_not_found", HttpStatus.NOT_FOUND),
APP_002("70002", "app.market.permission.denied", HttpStatus.FORBIDDEN),
APP_003("70003", "app.market.file.too_large", HttpStatus.BAD_REQUEST),
APP_004("70004", "app.market.file.type.unsupported", HttpStatus.BAD_REQUEST),
APP_005("70005", "app.market.version.conflict", HttpStatus.CONFLICT),
APP_006("70006", "app.market.name.duplicate", HttpStatus.CONFLICT),
APP_007("70007", "app.market.file.not_found", HttpStatus.NOT_FOUND),
APP_008("70008", "app.market.version.not_found", HttpStatus.NOT_FOUND);
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/common/ErrorCode.java
git commit -m "feat: add app market error codes APP_xxx"
```

---

### Task 3: 后端 - 实体类

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/appmarket/entity/AppMarket.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/entity/AppVersion.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/entity/AppFile.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/enums/AppType.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/enums/AppStatus.java`

- [ ] **Step 1: 创建 AppType 枚举**

```java
package com.yuyutian.mytools.appmarket.enums;

/**
 * 应用类型枚举。
 *
 * @author mytools
 * @since 2026-05-16
 */
public enum AppType {
    /** 富文本HTML内容 */
    APP("app"),
    /** 可执行二进制文件 */
    CLI("cli"),
    /** JSON配置文件 */
    MCP("mcp"),
    /** ZIP压缩包 */
    SKILL("skill");

    private final String value;

    AppType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AppType fromValue(String value) {
        for (AppType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown app type: " + value);
    }
}
```

- [ ] **Step 2: 创建 AppStatus 枚举**

```java
package com.yuyutian.mytools.appmarket.enums;

/**
 * 应用状态枚举。
 *
 * @author mytools
 * @since 2026-05-16
 */
public enum AppStatus {
    /** 已发布 */
    PUBLISHED("PUBLISHED"),
    /** 草稿 */
    DRAFT("DRAFT");

    private final String value;

    AppStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
```

- [ ] **Step 3: 创建 AppMarket 实体**

```java
package com.yuyutian.mytools.appmarket.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.yuyutian.mytools.appmarket.enums.AppStatus;
import com.yuyutian.mytools.appmarket.enums.AppType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用市场主表实体。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@TableName("t_app_market")
public class AppMarket {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private Long userId;

    private String name;

    private AppType type;

    private String version;

    private String thumbnailId;

    private String content;

    private String installCmd;

    private String downloadUrl;

    private AppStatus status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

- [ ] **Step 4: 创建 AppVersion 实体**

```java
package com.yuyutian.mytools.appmarket.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用历史版本表实体。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@TableName("t_app_version")
public class AppVersion {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String appId;

    private String version;

    private String content;

    private String fileId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
```

- [ ] **Step 5: 创建 AppFile 实体**

```java
package com.yuyutian.mytools.appmarket.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用文件表实体。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@TableName("t_app_file")
public class AppFile {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String appId;

    private String versionId;

    private String fileType;

    private String fileName;

    private String filePath;

    private Long fileSize;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/appmarket/entity/
git add src/main/java/com/yuyutian/mytools/appmarket/enums/
git commit -m "feat: add app market entity classes and enums"
```

---

### Task 4: 后端 - DTO 类

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/appmarket/dto/AppMarketCreateRequest.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/dto/AppMarketUpdateRequest.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/dto/AppMarketDetailResponse.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/dto/AppMarketListResponse.java`

- [ ] **Step 1: 创建 AppMarketCreateRequest**

```java
package com.yuyutian.mytools.appmarket.dto;

import com.yuyutian.mytools.appmarket.enums.AppType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 应用上架请求DTO。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
public class AppMarketCreateRequest {

    @NotBlank(message = "应用名称不能为空")
    @Size(max = 100, message = "应用名称最多100字符")
    private String name;

    @NotNull(message = "应用类型不能为空")
    private AppType type;

    @NotBlank(message = "版本号不能为空")
    @Size(max = 50, message = "版本号最多50字符")
    private String version;

    private String content;

    @Size(max = 500, message = "安装命令最多500字符")
    private String installCmd;

    @Size(max = 500, message = "外部下载链接最多500字符")
    private String downloadUrl;

    /** 上传的文件名（非必填，app类型可无文件） */
    private String fileName;

    /** 缩略图文件名（非必填） */
    private String thumbnailName;
}
```

- [ ] **Step 2: 创建 AppMarketUpdateRequest**

```java
package com.yuyutian.mytools.appmarket.dto;

import com.yuyutian.mytools.appmarket.enums.AppType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 应用编辑请求DTO。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
public class AppMarketUpdateRequest {

    @NotBlank(message = "版本号不能为空")
    @Size(max = 50, message = "版本号最多50字符")
    private String version;

    private String content;

    @Size(max = 500, message = "安装命令最多500字符")
    private String installCmd;

    @Size(max = 500, message = "外部下载链接最多500字符")
    private String downloadUrl;

    /** 上传的文件名（非必填） */
    private String fileName;

    /** 缩略图文件名（非必填） */
    private String thumbnailName;

    /** 版本更新说明（非必填） */
    private String versionNote;
}
```

- [ ] **Step 3: 创建 AppMarketDetailResponse**

```java
package com.yuyutian.mytools.appmarket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 应用详情响应DTO。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppMarketDetailResponse {

    private String id;

    private Long userId;

    private String userName;

    private String name;

    private String type;

    private String version;

    private String thumbnailId;

    private String thumbnailUrl;

    private String content;

    private String installCmd;

    private String downloadUrl;

    private String status;

    private LocalDateTime createdTime;

    private LocalDateTime updateTime;

    /** 当前版本文件ID（用于下载） */
    private String fileId;

    /** 当前文件名 */
    private String fileName;

    /** 当前文件大小 */
    private Long fileSize;

    /** 当前文件类型 */
    private String fileType;

    /** 缩略图文件路径 */
    private String thumbnailPath;

    /** 是否为所有者（前端权限判断用） */
    private Boolean isOwner;
}
```

- [ ] **Step 4: 创建 AppMarketListResponse**

```java
package com.yuyutian.mytools.appmarket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 应用列表响应DTO。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppMarketListResponse {

    private String id;

    private String name;

    private String type;

    private String version;

    private String thumbnailId;

    private String thumbnailUrl;

    private String contentPreview;

    private String status;

    private Long userId;

    private String userName;

    private LocalDateTime createdTime;

    private LocalDateTime updateTime;
}
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/appmarket/dto/
git commit -m "feat: add app market DTO classes"
```

---

### Task 5: 后端 - Mapper 类

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/appmarket/mapper/AppMarketMapper.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/mapper/AppVersionMapper.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/mapper/AppFileMapper.java`

- [ ] **Step 1: 创建 AppMarketMapper**

```java
package com.yuyutian.mytools.appmarket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuyutian.mytools.appmarket.entity.AppMarket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 应用市场 Mapper。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Mapper
public interface AppMarketMapper extends BaseMapper<AppMarket> {

    @Select("SELECT * FROM t_app_market WHERE status = 'PUBLISHED' " +
            "AND (#{type} IS NULL OR type = #{type}) " +
            "AND (#{name} IS NULL OR name LIKE CONCAT('%', #{name}, '%')) " +
            "ORDER BY created_time DESC")
    IPage<AppMarket> selectAppPage(Page<AppMarket> page,
                                   @Param("type") String type,
                                   @Param("name") String name);
}
```

- [ ] **Step 2: 创建 AppVersionMapper**

```java
package com.yuyutian.mytools.appmarket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyutian.mytools.appmarket.entity.AppVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 应用版本 Mapper。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Mapper
public interface AppVersionMapper extends BaseMapper<AppVersion> {

    @Select("SELECT * FROM t_app_version WHERE app_id = #{appId} ORDER BY created_time DESC")
    List<AppVersion> selectByAppId(@Param("appId") String appId);
}
```

- [ ] **Step 3: 创建 AppFileMapper**

```java
package com.yuyutian.mytools.appmarket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyutian.mytools.appmarket.entity.AppFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 应用文件 Mapper。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Mapper
public interface AppFileMapper extends BaseMapper<AppFile> {

    @Select("SELECT * FROM t_app_file WHERE app_id = #{appId} AND version_id IS NULL")
    List<AppFile> selectCurrentFilesByAppId(@Param("appId") String appId);

    @Select("SELECT * FROM t_app_file WHERE version_id = #{versionId}")
    List<AppFile> selectByVersionId(@Param("versionId") String versionId);

    @Select("SELECT * FROM t_app_file WHERE app_id = #{appId}")
    List<AppFile> selectAllByAppId(@Param("appId") String appId);
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/appmarket/mapper/
git commit -m "feat: add app market mapper classes"
```

---

### Task 6: 后端 - Service 层

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/appmarket/service/AppMarketService.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/service/impl/AppMarketServiceImpl.java`

- [ ] **Step 1: 创建 AppMarketService 接口**

```java
package com.yuyutian.mytools.appmarket.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yuyutian.mytools.appmarket.dto.*;
import com.yuyutian.mytools.appmarket.entity.AppFile;
import com.yuyutian.mytools.appmarket.entity.AppMarket;
import com.yuyutian.mytools.appmarket.entity.AppVersion;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 应用市场 Service 接口。
 *
 * @author mytools
 * @since 2026-05-16
 */
public interface AppMarketService {

    /**
     * 分页查询应用列表。
     */
    IPage<AppMarketListResponse> listApps(String type, String name, int page, int pageSize);

    /**
     * 获取应用详情。
     */
    AppMarketDetailResponse getAppDetail(String appId, Long currentUserId);

    /**
     * 上架新应用。
     */
    AppMarket createApp(AppMarketCreateRequest request, MultipartFile file,
                        MultipartFile thumbnail, Long userId) throws IOException;

    /**
     * 编辑应用（自动保存历史版本）。
     */
    AppMarket updateApp(String appId, AppMarketUpdateRequest request,
                         MultipartFile file, MultipartFile thumbnail,
                         Long userId) throws IOException;

    /**
     * 删除应用（含文件清理）。
     */
    void deleteApp(String appId, Long currentUserId);

    /**
     * 下架应用。
     */
    void offlineApp(String appId, Long currentUserId);

    /**
     * 上传应用文件。
     */
    AppFile uploadFile(String appId, String versionId, String fileType,
                       MultipartFile file, Long userId) throws IOException;

    /**
     * 下载文件。
     */
    AppFile getFile(String fileId);

    /**
     * 删除文件。
     */
    void deleteFile(String appId, String fileId, Long userId);

    /**
     * 获取应用历史版本列表。
     */
    List<AppVersion> getVersions(String appId);

    /**
     * 获取某版本详情。
     */
    AppVersion getVersionDetail(String appId, String versionId);

    /**
     * 获取文件下载路径。
     */
    String getFileDownloadPath(String fileId);
}
```

- [ ] **Step 2: 创建 AppMarketServiceImpl 实现类**

实现要点：
- `createApp`: 生成 Snowflake ID，保存主表，保存缩略图文件记录（若有），保存内容文件记录（若有）
- `updateApp`: 先查当前应用保存历史版本，再更新主表，保存新文件记录
- `deleteApp`: 检查权限，删除所有文件（从磁盘和数据库），删除历史版本，删除主表记录
- `offlineApp`: 检查权限，将 status 改为 DRAFT
- `uploadFile`: 生成 ID，保存到 `/opt/yuyutian/MyTools/app-market-files/{appId}/{fileType}/{uuid_filename}`，写入数据库
- `getFile`: 查 AppFile 记录
- `getFileDownloadPath`: 返回文件绝对路径

权限判断逻辑：
```java
boolean isAdmin = "ADMIN".equals(currentUserRole);
boolean isOwner = app.getUserId().equals(currentUserId);
if (!isAdmin && !isOwner) {
    throw new BusinessException(ErrorCode.APP_002);
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/appmarket/service/
git commit -m "feat: add app market service"
```

---

### Task 7: 后端 - Controller 层

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/appmarket/controller/AppMarketController.java`
- Create: `src/main/java/com/yuyutian/mytools/appmarket/controller/AppMarketFileController.java`
- Modify: `src/main/java/com/yuyutian/mytools/config/WebConfig.java`（添加静态文件映射）

- [ ] **Step 1: 创建 AppMarketController**

关键端点：
```
GET    /api/market/apps          -- 列表（type/name/page/pageSize）
GET    /api/market/apps/:id      -- 详情
POST   /api/market/apps          -- 上架（含 file 和 thumbnail 上传）
PUT    /api/market/apps/:id      -- 编辑（含 file 和 thumbnail 上传）
DELETE /api/market/apps/:id      -- 删除
PUT    /api/market/apps/:id/offline -- 下架
GET    /api/market/apps/:id/versions       -- 历史版本列表
GET    /api/market/apps/:id/versions/:vid  -- 某版本详情
```

获取当前用户ID：`Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader))`

- [ ] **Step 2: 创建 AppMarketFileController**

```
POST   /api/market/apps/:id/files           -- 上传文件（multipart）
GET    /api/market/files/:fileId/download   -- 下载文件（返回文件流）
DELETE /api/market/files/:fileId            -- 删除文件
```

下载端点返回 `ResponseEntity<Resource>`，设置 `Content-Disposition: attachment; filename=xxx`

- [ ] **Step 3: 修改 WebConfig 添加静态文件映射**

在 `WebConfig.java` 中添加资源映射：
```java
@Override
public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String fileDir = "/opt/yuyutian/MyTools/app-market-files/";
    registry.addResourceHandler("/market-files/**")
            .addResourceLocations("file:" + fileDir);
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/appmarket/controller/
git add src/main/java/com/yuyutian/mytools/config/WebConfig.java
git commit -m "feat: add app market controller and file download endpoint"
```

---

### Task 8: 前端 - API 函数

**Files:**
- Create: `webapp/src/service/api/appmarket.ts`
- Modify: `webapp/src/service/api/index.ts`（添加 export）

- [ ] **Step 1: 创建 API 函数**

```typescript
import { request, demoRequest } from '@/service/request';

// ========== 类型定义 ==========
export namespace Api.AppMarket {
  export interface AppItem {
    id: string;
    name: string;
    type: 'app' | 'cli' | 'mcp' | 'skill';
    version: string;
    thumbnailId: string | null;
    thumbnailUrl: string | null;
    contentPreview: string;
    status: 'PUBLISHED' | 'DRAFT';
    userId: number;
    userName: string;
    createdTime: string;
    updateTime: string;
  }

  export interface AppDetail {
    id: string;
    name: string;
    type: string;
    version: string;
    thumbnailId: string | null;
    thumbnailUrl: string | null;
    content: string | null;
    installCmd: string | null;
    downloadUrl: string | null;
    status: string;
    userId: number;
    userName: string;
    createdTime: string;
    updateTime: string;
    fileId: string | null;
    fileName: string | null;
    fileSize: number | null;
    fileType: string | null;
    thumbnailPath: string | null;
    isOwner: boolean;
  }

  export interface AppVersion {
    id: string;
    appId: string;
    version: string;
    content: string | null;
    fileId: string | null;
    createdTime: string;
  }

  export interface CreateAppRequest {
    name: string;
    type: string;
    version: string;
    content?: string;
    installCmd?: string;
    downloadUrl?: string;
  }

  export interface UpdateAppRequest {
    version: string;
    content?: string;
    installCmd?: string;
    downloadUrl?: string;
  }

  export interface ListResponse {
    list: AppItem[];
    total: number;
  }
}

// ========== API 函数 ==========

/** 分页获取应用列表 */
export function fetchGetAppList(params: {
  page: number;
  pageSize: number;
  type?: string;
  name?: string;
}) {
  return request<Api.AppMarket.ListResponse>({
    url: '/api/market/apps',
    method: 'GET',
    params
  });
}

/** 获取应用详情 */
export function fetchGetAppDetail(id: string) {
  return request<Api.AppMarket.AppDetail>({
    url: `/api/market/apps/${id}`,
    method: 'GET'
  });
}

/** 上架新应用 */
export function fetchCreateApp(data: Api.AppMarket.CreateAppRequest) {
  return request<Api.AppMarket.AppDetail>({
    url: '/api/market/apps',
    method: 'POST',
    data
  });
}

/** 编辑应用 */
export function fetchUpdateApp(id: string, data: Api.AppMarket.UpdateAppRequest) {
  return request<Api.AppMarket.AppDetail>({
    url: `/api/market/apps/${id}`,
    method: 'PUT',
    data
  });
}

/** 删除应用 */
export function fetchDeleteApp(id: string) {
  return request<void>({
    url: `/api/market/apps/${id}`,
    method: 'DELETE'
  });
}

/** 下架应用 */
export function fetchOfflineApp(id: string) {
  return request<void>({
    url: `/api/market/apps/${id}/offline`,
    method: 'PUT'
  });
}

/** 获取历史版本列表 */
export function fetchGetAppVersions(appId: string) {
  return request<Api.AppMarket.AppVersion[]>({
    url: `/api/market/apps/${appId}/versions`,
    method: 'GET'
  });
}

/** 获取某版本详情 */
export function fetchGetVersionDetail(appId: string, versionId: string) {
  return request<Api.AppMarket.AppVersion>({
    url: `/api/market/apps/${appId}/versions/${versionId}`,
    method: 'GET'
  });
}

/** 上传应用文件 */
export function fetchUploadAppFile(appId: string, file: File, fileType: string) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('fileType', fileType);
  return request<{ id: string; filePath: string }>({
    url: `/api/market/apps/${appId}/files`,
    method: 'POST',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  });
}

/** 上传缩略图 */
export function fetchUploadThumbnail(appId: string, file: File) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('fileType', 'thumbnail');
  return request<{ id: string; filePath: string }>({
    url: `/api/market/apps/${appId}/files`,
    method: 'POST',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  });
}

/** 删除文件 */
export function fetchDeleteFile(appId: string, fileId: string) {
  return request<void>({
    url: `/api/market/apps/${appId}/files/${fileId}`,
    method: 'DELETE'
  });
}

/** 获取文件下载URL */
export function getFileDownloadUrl(fileId: string): string {
  const baseURL = import.meta.env.VITE_API_URL || 'http://localhost:23110';
  return `${baseURL}/api/market/files/${fileId}/download`;
}
```

- [ ] **Step 2: 修改 index.ts 添加 export**

```typescript
export * from './appmarket';
```

- [ ] **Step 3: 提交**

```bash
git add webapp/src/service/api/appmarket.ts
git add webapp/src/service/api/index.ts
git commit -m "feat: add app market API functions"
```

---

### Task 9: 前端 - 列表页

**Files:**
- Create: `webapp/src/views/app-market/index.vue`

- [ ] **Step 1: 创建列表页组件**

关键功能：
1. 搜索栏：`类型`下拉、`名称`输入框、`查看历史版本`开关、`搜索`按钮
2. 右上角 `上架` 按钮
3. 表格列：序号、类型、应用缩略图、名称、版本、简介（截断50字）、上架时间、操作（下载 / 编辑 / 删除）
4. 分页组件
5. 打开 `查看历史版本` 时，每行展开显示历史版本列表
6. 操作按钮权限判断：`isAdmin || isOwner` 显示编辑/删除，否则只显示下载

```vue
<script setup lang="ts">
import { reactive, h, computed } from 'vue';
import {
  fetchGetAppList, fetchDeleteApp, fetchOfflineApp,
  fetchGetAppVersions
} from '@/service/api/appmarket';
import { useAuthStore } from '@/store/modules/auth';
import { useLoading } from '@sa/hooks';
import {
  NButton, NTag, NSpace, NImage, NSwitch, NInput, NSelect,
  NDataTable, NPagination, NModal, NCard, NEmpty, useMessage,
  useDialog, NPopconfirm
} from 'naive-ui';
import AppMarketDrawer from './components/AppMarketDrawer.vue';

defineOptions({ name: 'AppMarket' });

const message = useMessage();
const dialog = useDialog();
const authStore = useAuthStore();
const { loading, startLoading, endLoading } = useLoading();

const isAdmin = computed(() => authStore.userInfo.role === 'ADMIN');
const currentUserId = computed(() => authStore.userInfo.id);

// 搜索条件
const searchForm = reactive({
  type: null as string | null,
  name: '',
  includeHistory: false
});

// 分页
const pagination = reactive({
  page: 1,
  pageSize: 10,
  total: 0
});

// 数据
const data: Api.AppMarket.AppItem[] = reactive([]);
const expandedRows = reactive<Set<string>>(new Set());

// 侧滑
const drawer = reactive({
  show: false,
  mode: 'detail' as 'detail' | 'publish',
  appId: null as string | null
});

// 表格列定义
const columns = [
  { title: '序号', key: 'index', width: 60, render: (_: any, index: number) =>
    (pagination.page - 1) * pagination.pageSize + index + 1 },
  { title: '类型', key: 'type', width: 80, render: (row: Api.AppMarket.AppItem) =>
    h(NTag, { size: 'small', type: getTypeTagType(row.type) }, () => row.type.toUpperCase()) },
  { title: '缩略图', key: 'thumbnailUrl', width: 80,
    render: (row: Api.AppMarket.AppItem) =>
      row.thumbnailUrl
        ? h(NImage, { src: row.thumbnailUrl, width: 48, height: 48, objectFit: 'cover', style: { borderRadius: '4px' } })
        : h('div', { style: { width: '48px', height: '48px', background: '#f5f5f5', borderRadius: '4px', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999', fontSize: '12px' } }, '无')
  },
  { title: '名称', key: 'name', width: 120, ellipsis: { tooltip: true } },
  { title: '版本', key: 'version', width: 80 },
  { title: '简介', key: 'contentPreview', ellipsis: { tooltip: true },
    render: (row: Api.AppMarket.AppItem) => row.contentPreview || '-' },
  { title: '上架时间', key: 'createdTime', width: 140, ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render: (row: Api.AppMarket.AppItem) => {
      const btns = [];

      // 下载按钮（所有人可见）
      btns.push(h(NButton, {
        size: 'tiny', type: 'info', onClick: () => handleDownload(row),
        style: { marginRight: '6px' }
      }, () => '下载'));

      // 编辑/删除（仅管理员或所有者）
      if (isAdmin.value || row.userId === currentUserId.value) {
        btns.push(h(NButton, {
          size: 'tiny', type: 'warning', onClick: () => openDrawer('publish', row.id),
          style: { marginRight: '6px' }
        }, () => '编辑'));

        btns.push(h(NPopconfirm, {
          onPositiveClick: () => handleDelete(row)
        }, {
          trigger: () => h(NButton, { size: 'tiny', type: 'error' }, () => '删除'),
          default: () => `确定删除 "${row.name}" 吗？`
        }));
      }

      return btns;
    }
  }
];

function getTypeTagType(type: string): 'default' | 'success' | 'info' | 'warning' | 'error' {
  const map: Record<string, 'default' | 'success' | 'info' | 'warning' | 'error'> = {
    app: 'success', cli: 'info', mcp: 'warning', skill: 'error'
  };
  return map[type] || 'default';
}

async function loadData() {
  startLoading();
  try {
    const { list, total } = await fetchGetAppList({
      page: pagination.page,
      pageSize: pagination.pageSize,
      type: searchForm.type || undefined,
      name: searchForm.name || undefined
    });
    data.length = 0;
    data.push(...list);
    pagination.total = total;
  } finally {
    endLoading();
  }
}

async function handleDelete(row: Api.AppMarket.AppItem) {
  await fetchDeleteApp(row.id);
  message.success('删除成功');
  loadData();
}

function handleDownload(row: Api.AppMarket.AppItem) {
  if (!row.id) return;
  const url = `${import.meta.env.VITE_API_URL}/api/market/files/${row.id}/download`;
  window.open(url, '_blank');
}

function openDrawer(mode: 'detail' | 'publish', appId?: string) {
  drawer.mode = mode;
  drawer.appId = appId || null;
  drawer.show = true;
}

function handleDrawerClose() {
  drawer.show = false;
  loadData();
}

function handleSearch() {
  pagination.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.page = page;
  loadData();
}

function handlePageSizeChange(pageSize: number) {
  pagination.pageSize = pageSize;
  pagination.page = 1;
  loadData();
}

loadData();
</script>

<template>
  <div>
    <NCard :bordered="false">
      <!-- 搜索栏 -->
      <NSpace vertical :size="12">
        <NSpace>
          <NSelect
            v-model:value="searchForm.type"
            placeholder="应用类型"
            :options="[
              { label: '全部类型', value: null },
              { label: 'App', value: 'app' },
              { label: 'CLI', value: 'cli' },
              { label: 'MCP', value: 'mcp' },
              { label: 'Skill', value: 'skill' }
            ]"
            clearable
            style="width: 140px"
          />
          <NInput
            v-model:value="searchForm.name"
            placeholder="应用名称"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
          <NSpace>
            <span style="font-size: 14px; color: #666;">查看历史版本</span>
            <NSwitch v-model:value="searchForm.includeHistory" />
          </NSpace>
          <NButton type="primary" @click="handleSearch">搜索</NButton>
          <NButton @click="loadData">刷新</NButton>
          <NButton type="primary" @click="openDrawer('publish')">上架</NButton>
        </NSpace>

        <NDataTable
          :columns="columns"
          :data="data"
          :loading="loading"
          :pagination="false"
          :scroll-x="900"
          :row-key="(row: Api.AppMarket.AppItem) => row.id"
        />

        <NSpace justify="end" style="margin-top: 12px">
          <NPagination
            v-model:page="pagination.page"
            :page-size="pagination.pageSize"
            :page-sizes="[10, 20, 50]"
            :total="pagination.total"
            show-size-picker
            @update:page="handlePageChange"
            @update:page-size="handlePageSizeChange"
          />
        </NSpace>
      </NSpace>
    </NCard>

    <!-- 侧滑详情/上架 -->
    <AppMarketDrawer
      v-model:show="drawer.show"
      :mode="drawer.mode"
      :app-id="drawer.appId"
      @close="handleDrawerClose"
    />
  </div>
</template>
```

- [ ] **Step 2: 提交**

```bash
git add webapp/src/views/app-market/index.vue
git commit -m "feat: add app market list page"
```

---

### Task 10: 前端 - 侧滑组件

**Files:**
- Create: `webapp/src/views/app-market/components/AppMarketDrawer.vue`

- [ ] **Step 1: 创建 AppMarketDrawer.vue**

两种 mode：`detail`（详情）和 `publish`（上架/编辑）

```vue
<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import {
  fetchGetAppDetail, fetchCreateApp, fetchUpdateApp,
  fetchGetAppVersions, fetchUploadThumbnail, fetchUploadAppFile,
  fetchOfflineApp, getFileDownloadUrl
} from '@/service/api/appmarket';
import { useAuthStore } from '@/store/modules/auth';
import { useLoading } from '@sa/hooks';
import {
  NDrawer, NDrawerContent, NButton, NSpace, NTabs, NTabPane,
  NTag, NImage, NInput, NSelect, NForm, NFormItem,
  NAlert, NEmpty, NSpin, NText, NDivider,
  useMessage, useDialog, NPopconfirm
} from 'naive-ui';
import type { UploadFileInfo } from 'naive-ui';

const props = defineProps<{
  show: boolean;
  mode: 'detail' | 'publish';
  appId: string | null;
}>();

const emit = defineEmits<{
  (e: 'update:show', val: boolean): void;
  (e: 'close'): void;
}>();

const message = useMessage();
const dialog = useDialog();
const authStore = useAuthStore();
const { loading, startLoading, endLoading } = useLoading();

const isAdmin = computed(() => authStore.userInfo.role === 'ADMIN');
const isOwner = computed(() => {
  if (!detail.value) return false;
  return detail.value.userId === authStore.userInfo.id;
});
const canEdit = computed(() => isAdmin.value || isOwner.value);

// 详情数据
const detail = ref<Api.AppMarket.AppDetail | null>(null);

// 历史版本
const versions = ref<Api.AppMarket.AppVersion[]>([]);

// 上架表单
const form = ref({
  name: '',
  type: 'app' as 'app' | 'cli' | 'mcp' | 'skill',
  version: '1.0.0',
  content: '',
  installCmd: '',
  downloadUrl: ''
});

const submitting = ref(false);
const uploadingThumbnail = ref(false);
const uploadingFile = ref(false);

const isEdit = computed(() => props.mode === 'publish' && !!props.appId);

watch(() => props.show, async (val) => {
  if (val) {
    if (props.mode === 'detail' && props.appId) {
      await loadDetail();
      await loadVersions();
    } else if (props.mode === 'publish') {
      if (props.appId) {
        await loadDetail();
        form.value = {
          name: detail.value?.name || '',
          type: (detail.value?.type as any) || 'app',
          version: detail.value?.version || '',
          content: detail.value?.content || '',
          installCmd: detail.value?.installCmd || '',
          downloadUrl: detail.value?.downloadUrl || ''
        };
      } else {
        form.value = { name: '', type: 'app', version: '1.0.0', content: '', installCmd: '', downloadUrl: '' };
        detail.value = null;
      }
      versions.value = [];
    }
  }
});

async function loadDetail() {
  if (!props.appId) return;
  startLoading();
  try {
    detail.value = await fetchGetAppDetail(props.appId);
  } finally {
    endLoading();
  }
}

async function loadVersions() {
  if (!props.appId) return;
  versions.value = await fetchGetAppVersions(props.appId);
}

async function handleSubmit() {
  if (!form.value.name.trim()) {
    message.warning('请填写应用名称');
    return;
  }
  if (!form.value.version.trim()) {
    message.warning('请填写版本号');
    return;
  }

  submitting.value = true;
  try {
    if (isEdit.value) {
      await fetchUpdateApp(props.appId!, {
        version: form.value.version,
        content: form.value.content,
        installCmd: form.value.installCmd || undefined,
        downloadUrl: form.value.downloadUrl || undefined
      });
      message.success('更新成功');
    } else {
      await fetchCreateApp({
        name: form.value.name,
        type: form.value.type,
        version: form.value.version,
        content: form.value.content || undefined,
        installCmd: form.value.installCmd || undefined,
        downloadUrl: form.value.downloadUrl || undefined
      });
      message.success('上架成功');
    }
    emit('close');
  } finally {
    submitting.value = false;
  }
}

async function handleThumbnailUpload(options: { file: UploadFileInfo }) {
  if (!props.appId) return;
  uploadingThumbnail.value = true;
  try {
    const file = (options.file as any).file;
    await fetchUploadThumbnail(props.appId, file);
    message.success('缩略图上传成功');
    await loadDetail();
  } catch {
    message.error('缩略图上传失败');
  } finally {
    uploadingThumbnail.value = false;
  }
}

async function handleFileUpload(options: { file: UploadFileInfo }) {
  if (!props.appId) return;
  uploadingFile.value = true;
  try {
    const file = (options.file as any).file;
    await fetchUploadAppFile(props.appId, file, 'binary');
    message.success('文件上传成功');
    await loadDetail();
  } catch {
    message.error('文件上传失败');
  } finally {
    uploadingFile.value = false;
  }
}

function handleDownload() {
  if (!detail.value?.fileId) {
    message.warning('该应用暂无下载文件');
    return;
  }
  const url = getFileDownloadUrl(detail.value.fileId);
  window.open(url, '_blank');
}

async function handleOffline() {
  if (!props.appId) return;
  await fetchOfflineApp(props.appId);
  message.success('下架成功');
  emit('close');
}

function handleClose() {
  emit('update:show', false);
  emit('close');
}

function stripHtml(html: string): string {
  if (!html) return '';
  return html.replace(/<[^>]+>/g, '').trim();
}
</script>

<template>
  <NDrawer
    :show="props.show"
    display-directive="show"
    :width="560"
    :mask-closable="true"
    @update:show="(val) => emit('update:show', val)"
  >
    <NDrawerContent
      :title="isEdit ? '编辑应用' : (props.mode === 'detail' ? '应用详情' : '上架新应用')"
      :native-scrollbar="false"
      closable
    >
      <!-- Detail 模式 -->
      <template v-if="props.mode === 'detail'">
        <NSpin :show="loading">
          <div v-if="detail">
            <NSpace vertical :size="16">
              <!-- 缩略图 -->
              <div v-if="detail.thumbnailUrl" style="text-align: center;">
                <NImage :src="detail.thumbnailUrl" width="200" height="200" object-fit="cover" style="border-radius: 8px;" />
              </div>
              <div v-else style="text-align:center; color:#999; font-size:13px;">暂无缩略图</div>

              <!-- 基本信息 -->
              <NTag :type="detail.type === 'app' ? 'success' : detail.type === 'cli' ? 'info' : detail.type === 'mcp' ? 'warning' : 'error'" size="large">
                {{ detail.type.toUpperCase() }}
              </NTag>
              <div><strong>名称：</strong>{{ detail.name }}</div>
              <div><strong>版本：</strong>{{ detail.version }}</div>
              <div><strong>发布人：</strong>{{ detail.userName }}（ID: {{ detail.userId }}）</div>
              <div><strong>上架时间：</strong>{{ detail.createdTime }}</div>
              <div v-if="detail.installCmd"><strong>安装命令：</strong><code>{{ detail.installCmd }}</code></div>
              <div v-if="detail.downloadUrl"><strong>外部链接：</strong><a :href="detail.downloadUrl" target="_blank">{{ detail.downloadUrl }}</a></div>

              <!-- 简介内容 -->
              <NDivider>应用简介</NDivider>
              <div v-if="detail.content" v-html="detail.content" style="line-height:1.8; color:#333;"></div>
              <NEmpty v-else description="暂无简介" />

              <!-- 历史版本 -->
              <NDivider>历史版本</NDivider>
              <NSpace v-if="versions.length" vertical :size="8">
                <div v-for="v in versions" :key="v.id" style="padding: 8px; background: #f5f5f5; border-radius: 4px; font-size: 13px;">
                  <strong>v{{ v.version }}</strong> — {{ v.createdTime }}
                </div>
              </NSpace>
              <NEmpty v-else description="暂无历史版本" />
            </NSpace>
          </div>
        </NSpin>

        <!-- 操作按钮 -->
        <template #footer>
          <NSpace justify="center">
            <NButton @click="handleClose">关闭</NButton>
            <NButton type="info" @click="handleDownload" :disabled="!detail?.fileId">下载</NButton>
            <NButton v-if="canEdit" type="warning" @click="emit('update:show', false); $emit('close');">编辑</NButton>
            <NPopconfirm v-if="canEdit" @positive-click="handleOffline">
              <template #trigger>
                <NButton type="error">下架</NButton>
              </template>
              确定下架该应用？下架后可重新上架。
            </NPopconfirm>
          </NSpace>
        </template>
      </template>

      <!-- Publish 模式 -->
      <template v-else>
        <NForm label-placement="left" label-width="90">
          <NFormItem label="应用名称" required>
            <NInput v-model:value="form.name" placeholder="请输入应用名称" :disabled="isEdit" />
          </NFormItem>
          <NFormItem label="应用类型" v-if="!isEdit">
            <NSelect
              v-model:value="form.type"
              :options="[
                { label: 'App（富文本HTML）', value: 'app' },
                { label: 'CLI（二进制）', value: 'cli' },
                { label: 'MCP（JSON配置）', value: 'mcp' },
                { label: 'Skill（ZIP包）', value: 'skill' }
              ]"
            />
          </NFormItem>
          <NFormItem label="版本号" required>
            <NInput v-model:value="form.version" placeholder="如 1.0.0" />
          </NFormItem>
          <NFormItem label="应用简介">
            <!-- 富文本编辑器：使用 v-html contenteditable 简化实现 -->
            <div style="border: 1px solid #d9d9d9; border-radius: 4px; padding: 8px;">
              <div
                contenteditable="true"
                v-html="form.content"
                @input="(e) => form.content = (e.target as HTMLElement).innerHTML"
                style="min-height: 120px; outline: none; line-height: 1.6;"
                placeholder="请输入应用简介..."
              />
            </div>
            <NText depth="3" style="font-size: 12px; margin-top: 4px;">
              支持HTML富文本格式（&lt;b&gt;、&lt;i&gt;、&lt;code&gt;、&lt;ul&gt;等）
            </NText>
          </NFormItem>
          <NFormItem label="安装命令">
            <NInput v-model:value="form.installCmd" placeholder="如：npm install -g my-cli" />
          </NFormItem>
          <NFormItem label="外部下载链接">
            <NInput v-model:value="form.downloadUrl" placeholder="如有外部下载链接可填" />
          </NFormItem>
        </NForm>

        <template #footer>
          <NSpace justify="center">
            <NButton @click="handleClose">取消</NButton>
            <NButton type="primary" :loading="submitting" @click="handleSubmit">
              {{ isEdit ? '保存更新' : '确认上架' }}
            </NButton>
          </NSpace>
        </template>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>
```

- [ ] **Step 2: 提交**

```bash
git add webapp/src/views/app-market/components/AppMarketDrawer.vue
git commit -m "feat: add app market drawer component"
```

---

### Task 11: 前端 - i18n 和菜单图标

**Files:**
- Modify: `webapp/src/locales/langs/zh-cn.ts`（添加 route.app-market 等 i18n key）
- Modify: `webapp/src/router/elegant/routes.ts`（添加 app-market 路由元数据，icon: 'mdi:store'）

- [ ] **Step 1: 添加 i18n key**

在 `zh-cn.ts` 的 `route` 部分添加：

```typescript
// 在 route 对象的现有项后添加
app_market: '应用市场',
'app-market_index': '应用列表',
```

- [ ] **Step 2: 添加路由元数据**

在 `webapp/src/router/elegant/routes.ts` 的 routes 数组中添加：

```typescript
{
  name: 'app-market',
  path: '/app-market',
  meta: {
    title: 'app-market',
    i18nKey: 'route.app-market',
    icon: 'mdi:store',
    order: 5
  },
  children: [
    {
      name: 'app-market_index',
      path: '/app-market/index',
      component: 'view.app-market_index',
      meta: {
        title: 'app-market_index',
        i18nKey: 'route.app-market_index',
        icon: 'mdi:application-outline'
      }
    }
  ]
}
```

注意：`webapp/src/router/elegant/routes.ts` 是自动生成的文件（由 elegant-router 根据 `src/views/` 目录生成）。如果存在该文件可直接编辑，但最佳实践是在 `src/views/app-market/index.vue` 所在目录中由框架自动识别。

实际项目中，Elegant Router 会自动扫描 `src/views/` 目录生成路由。在 `views/app-market/index.vue` 创建后，路由自动生成，无需手动注册。

- [ ] **Step 3: 提交**

```bash
git add webapp/src/locales/langs/zh-cn.ts
git add webapp/src/router/elegant/routes.ts
git commit -m "feat: add app market i18n and route metadata"
```

---

### Task 12: 联调测试

- [ ] **Step 1: 后端编译验证**

```bash
cd /Users/pankang/mycode/MyTools
mvn compile -q
```

预期：无编译错误

- [ ] **Step 2: 前端类型检查**

```bash
cd /Users/pankang/mycode/MyTools/webapp
pnpm typecheck
```

预期：无 TypeScript 类型错误

- [ ] **Step 3: 启动后端并测试 API**

```bash
# 启动后端（后台）
mvn spring-boot:run -q &

# 测试列表接口
curl -s "http://localhost:23110/api/market/apps?page=1&pageSize=10"

# 测试上架接口（需要先登录获取 token）
TOKEN="Bearer YOUR_TOKEN"
curl -s -X POST "http://localhost:23110/api/market/apps" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"测试CLI","type":"CLI","version":"1.0.0"}'
```

- [ ] **Step 4: 前端启动验证**

```bash
cd /Users/pankang/mycode/MyTools/webapp
pnpm dev
```

访问 http://localhost:5173/app-market，检查：
- [ ] 菜单中显示"应用市场"菜单项（带 mdi:store 图标）
- [ ] 列表页正常加载
- [ ] 点击"上架"按钮打开侧滑表单
- [ ] 上架后列表显示新应用
- [ ] 点击列表项打开详情侧滑
- [ ] 编辑/删除按钮权限正常（非管理员/非所有者只看到下载按钮）
- [ ] 下载按钮能正确下载文件
- [ ] 查看历史版本开关能展开历史版本

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat: complete app market feature implementation"
```

---

## 三、任务依赖关系

```
Task 1 (数据库迁移)
    ↓
Task 2 (错误码) → Task 3 (实体类)
    ↓                           ↓
Task 4 (DTO) → Task 5 (Mapper)
    ↓
Task 6 (Service) → Task 7 (Controller)
    ↓
Task 8 (前端API) → Task 9 (前端列表) → Task 10 (前端侧滑)
    ↓                                          ↓
    ← ← ← ← ← ← ← Task 11 (i18n+菜单) ← ← ← ← ←
    ↓
Task 12 (联调测试)
```

---

## 四、Spec 覆盖自检

| 设计要求 | 实现位置 |
|---------|---------|
| 3张表 t_app_market/version/file | Task 1 |
| 9个API端点 | Task 7 |
| app/cli/mcp/skill 类型 | Task 3 (枚举), Task 10 (表单) |
| 文件存储到 /opt/.../app-market-files/ | Task 6 (ServiceImpl) |
| 缩略图上传 | Task 7 (Controller), Task 10 (Drawer) |
| 富文本编辑器 | Task 10 (Drawer contenteditable) |
| 权限矩阵（下载所有人，编辑/删除仅管理员或所有者） | Task 6 (Service), Task 9 (列表页) |
| 历史版本自动保存 | Task 6 (updateApp) |
| 前端列表+搜索+分页 | Task 9 |
| 侧滑详情+上架组件 | Task 10 |
| i18n | Task 11 |
| 前端 string ID | Task 8 (Api.AppMarket 所有 ID 为 string) |

---

## 五、类型一致性检查

- `Api.AppMarket.AppItem.id` → `string`
- `Api.AppMarket.AppDetail.id` → `string`
- `Api.AppMarket.AppVersion.id` → `string`
- `AppMarket.id` (Java) → `VARCHAR(19)` → 前端 `string`
- `AppMarket.userId` (Java) → `BIGINT` → 前端 `number`
- `AppMarketCreateRequest.type` → `AppType` enum → 前端 `string` ('app'|'cli'|'mcp'|'skill')
- `AppMarketDetailResponse.isOwner` → `boolean`

---
