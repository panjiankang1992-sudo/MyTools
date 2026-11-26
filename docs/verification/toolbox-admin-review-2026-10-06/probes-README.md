# 教材后端验收探针复跑

这两个探针调用 `service/toolbox-service/target/classes` 中实际编译的应用类，不复制业务实现。它们没有调用生产 HTTP 服务、没有使用管理员凭据、没有写生产数据库，结果不是生产实测。

- `CurriculumReviewProbe.java`：纯内存调用校验器和映射器，检查未核对、非法拼音、跨单元课文、重复顺序和前插 ID 碰撞。
- `CurriculumPersistenceProbe.java`：独立内存 H2，只读取仓库教材文件；进程结束后数据销毁。检查迁移草稿、筛选、非法草稿保存及回执故障。它直接实例化仓储，**不验证 Spring 事务代理或真实 MySQL 并发行为**；事务边界结论必须同时参照源码与集成测试。

输出中的 `publishable=true`、重复 ID、空导入草稿和回执失败后 revision 推进是问题证据，不是验收通过。修复后这些输出应发生相应变化。

## 准备实际编译类与测试依赖

在仓库根目录执行后端测试。测试需要允许本机随机端口监听；使用测试配置和隔离 H2，不能切成生产配置。

```bash
cd /Users/pankang/mycode/MyTools
mvn -o -f service/toolbox-service/pom.xml test
```

`-o` 仅适用于本机依赖齐备。测试报告与 `target/classes` 必须对应当前源码，不能用过期编译结果验收新修改。

## 编译并运行两份探针

以下脚本从现有 Surefire XML 提取本轮测试类路径，使用参数数组调用 Java，不输出完整类路径、不依赖 `/tmp` 中的历史文件、不向应用源码或已保存证据写入。

```bash
cd /Users/pankang/mycode/MyTools
python3 - <<'PY'
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

root = Path.cwd()
service = root / 'service/toolbox-service'
reports = sorted((service / 'target/surefire-reports').glob('TEST-*.xml'))
classpath = None
for report in reports:
    tree = ET.parse(report)
    classpath = next((
        item.attrib.get('value')
        for item in tree.findall('./properties/property')
        if item.attrib.get('name') == 'java.class.path'
    ), None)
    if classpath:
        break
if not classpath:
    raise SystemExit('No Surefire test classpath found; run the backend tests first.')

evidence = root / 'docs/verification/toolbox-admin-review-2026-10-06'
probe_names = ['CurriculumReviewProbe', 'CurriculumPersistenceProbe']
with tempfile.TemporaryDirectory(prefix='toolbox-admin-review-probes-') as compiled:
    subprocess.run([
        'javac', '-cp', classpath, '-d', compiled,
        *(str(evidence / (name + '.java')) for name in probe_names)
    ], cwd=root, check=True)
    for name in probe_names:
        print('\nRunning ' + name, flush=True)
        subprocess.run([
            'java', '-cp', compiled + ':' + classpath, name
        ], cwd=root, check=True)
PY
```

原始验收结果保存在 `curriculum-probe-output.txt` 和 `curriculum-persistence-probe-output.txt`。修复后另存新的验证目录，不覆盖这些历史反例。

鉴权 HTTP 探针另见 `run-admin-auth-review-probe.py`；它启动真实本机 Spring HTTP 服务，但身份适配器仍为注入式测试实现。前端探针与线上公开 E2E 的命令及边界见 `review-and-plan.md` 第 8 节。
