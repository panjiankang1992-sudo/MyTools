# Object collection reconciliation digest

Drive Service 与 Storage Gateway 对同一对象集合使用相同的确定性摘要协议：

1. 按对象相对路径升序排序。
2. 每个对象依次编码 `path`、`name`、小写布尔值 `directory`、十进制 `sizeBytes`、微秒精度 UTC `modifiedAt`、小写 `contentSha256`；空值编码为空字符串。
3. 每个 UTF-8 字段前写入四字节大端无符号长度，再写字段字节。
4. 对完整字节流计算 SHA-256，并同时返回对象数量。

切换必须同时满足 `itemCount` 和 `contentSha256` 相等。黄金向量：

```text
path=a.txt
name=a.txt
directory=false
sizeBytes=3
modifiedAt=2026-01-01T00:00:00Z
contentSha256=

digest=8501ff9beb116985f2ad48e3e4417e85c1f0121b8498a344a7fb307b51314879
```
