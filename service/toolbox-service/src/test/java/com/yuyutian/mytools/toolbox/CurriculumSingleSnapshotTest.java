package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C06：**单个请求只取得一次 `CatalogSnapshot`**（设计第 328 行）。
 *
 * 这条要求今天**事实上成立**，但**是靠约定成立的，不是结构上保证的**：
 *
 * - `CurriculumCatalog` 的每个公开读取方法（`catalog`／`version`／`entries`…）
 *   各自只调用一次 `snapshot.get()`，之后从那个**不可变快照**里取全部数据；
 * - 每个公开端点只调用**其中一个**方法。
 *
 * 所以"一个请求 = 一次快照"成立。**但没有任何东西阻止将来有人在同一个端点里
 * 调两个方法**——那时两次 `snapshot.get()` 之间可能发生发布，
 * 于是同一次响应里目录与条目来自不同代（正是设计里点名要避免的情形）。
 *
 * 这条测试把那个约定**变成会失败的断言**：若有人在同一个方法里读两次快照，
 * 或者让某个公开方法改从别处多次取快照，它会立刻报警。
 *
 * **它证明的是"结构上没有多处取快照"，不是"运行时一定一致"**——
 * 后者的完整证明需要并发注入测试，这里没有做。**范围写清楚，不含糊。**
 */
class CurriculumSingleSnapshotTest {

    private static final Path CATALOG = Path.of(
            "src/main/java/com/yuyutian/mytools/toolbox/service/curriculum/CurriculumCatalog.java");

    @Test
    @DisplayName("没有哪个方法会读两次快照（同一次响应不会跨两代）")
    void noMethodReadsSnapshotTwice() throws Exception {
        String source = Files.readString(CATALOG, StandardCharsets.UTF_8);
        // 粗粒度但有效的检查：把源码按方法起始切成段，逐段数 snapshot.get() 的次数。
        // 不做完整语法分析——这条测试要挡的是"顺手多读一次"，不是刁钻写法。
        Pattern methodStart = Pattern.compile("(?m)^    (?:public|private|protected)[^;{]*\\{\\s*$");
        Matcher matcher = methodStart.matcher(source);
        int checked = 0;
        int index = 0;
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : source.length();
            String body = source.substring(from, to);
            long reads = body.split("snapshot\\.get\\(\\)", -1).length - 1L;
            assertThat(reads)
                    .as("方法「%s」读了 %d 次快照；一次响应只应取一次",
                            body.substring(0, Math.min(body.indexOf('{'), 60)).trim(), reads)
                    .isLessThanOrEqualTo(1);
            checked++;
        }
        assertThat(checked).as("至少要真的检查到若干方法，否则这条测试是空转").isGreaterThan(3);
        index++;
        assertThat(index).isEqualTo(1);
    }
}
