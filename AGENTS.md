# AGENTS.md

## 项目概况

- Java 21 + Spring Boot + Maven
- 代码注释统一使用中文
- 代码中除注释外不允许出现中文（即：变量名、方法名、包名、配置文件等均用英文）

## 分层结构

```
src/main/java/com/xxx/{module}/
├── controller/     # 控制层，按功能分子包
├── Model/          # 数据模型（实体类）
├── mapper/         # 数据访问层
├── service/        # 业务逻辑层，按功能分子包
└── utils/          # 工具类，按功能分子包
```

包划分按具体功能分子包，例如：`controller/user/`、`service/order/`、`mapper/product/`。

## 代码规范

### 注释要求

- **必须**：所有 `public` 方法必须有 Javadoc 注释
- **必须**：关键逻辑（if/循环/重要赋值）需行内注释说明
- **必须**：枚举、配置类、常量类必须有类级注释
- 注释使用中文句子的标点符号（。）

### 代码风格

- 变量/方法/包名：**英文**，禁止中文
- 不允许出现任何 Lint 警告或 IDE 规范告警
- 所有警告必须在提交前修复

## 错误码管理

- 错误码统一在 `common/ErrorCode.java` 中管理（枚举或常量类）
- 所有业务异常通过错误码抛出，禁止硬编码错误信息
- 错误码格式：`模块_序号`，如 `USER_001`、`ORDER_002`

## 构建与验证

- 编译命令：`mvn compile`
- 打包命令：`mvn package -DskipTests`
- 修复任何编译警告后再提交

<!-- SPECKIT START -->
**Implementation Plan**: `specs/001-user-management-backend/plan.md`
- 包含完整技术决策：多数据源、JWT、异常拦截、日志、测试、雪花算法
- 包含数据模型：`specs/001-user-management-backend/data-model.md`
- 包含API协议：`specs/001-user-management-backend/contracts/user-api.md`
- 包含研究文档：`specs/001-user-management-backend/research.md`
<!-- SPECKIT END -->
