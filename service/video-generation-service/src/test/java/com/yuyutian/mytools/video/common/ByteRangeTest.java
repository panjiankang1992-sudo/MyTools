package com.yuyutian.mytools.video.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Range 解析的边界行为：播放器会发各种形态的区间，任何一种都不能变成 500。 */
class ByteRangeTest {
    @Test
    void absentWithoutHeaderOrOnMalformedForms() {
        // 说明：`bytes=-0`（零长度后缀）与 `bytes=100-`（起点越界）不是"畸形头"而是"无法满足"，
        // 它们由 outOfRangeStartIsUnsatisfiable 覆盖。
        for (String header : new String[]{null, "", "items=0-1", "bytes=", "bytes=abc", "bytes=1-2,5-6",
                "bytes=5-3", "bytes=-", "bytes=99999999999999999999999-"}) {
            assertEquals(ByteRange.Kind.ABSENT, ByteRange.parse(header, 100).kind(), String.valueOf(header));
        }
    }

    @Test
    void satisfiedRangesAreClamped() {
        ByteRange head = ByteRange.parse("bytes=0-9", 100);
        assertEquals(ByteRange.Kind.SATISFIED, head.kind());
        assertEquals(0, head.start());
        assertEquals(9, head.end());
        assertEquals(10, head.size());
        assertEquals("bytes 0-9/100", head.contentRange(100));

        ByteRange open = ByteRange.parse("bytes=90-", 100);
        assertEquals(90, open.start());
        assertEquals(99, open.end());

        ByteRange suffix = ByteRange.parse("bytes=-10", 100);
        assertEquals(90, suffix.start());
        assertEquals(99, suffix.end());

        // 超出末尾的 end 收敛到最后一字节，而不是报错。
        ByteRange clamped = ByteRange.parse("bytes=95-500", 100);
        assertEquals(99, clamped.end());
        assertEquals(5, clamped.size());
    }

    @Test
    void suffixLongerThanResourceStartsAtZero() {
        ByteRange range = ByteRange.parse("bytes=-500", 100);
        assertEquals(0, range.start());
        assertEquals(99, range.end());
    }

    @Test
    void outOfRangeStartIsUnsatisfiable() {
        assertEquals(ByteRange.Kind.UNSATISFIABLE, ByteRange.parse("bytes=100-", 100).kind());
        assertEquals(ByteRange.Kind.UNSATISFIABLE, ByteRange.parse("bytes=150-200", 100).kind());
        // 零长度后缀无法满足，回 416 而不是当成完整响应。
        assertEquals(ByteRange.Kind.UNSATISFIABLE, ByteRange.parse("bytes=-0", 100).kind());
        // 空资源上任何区间都无法满足。
        assertEquals(ByteRange.Kind.UNSATISFIABLE, ByteRange.parse("bytes=0-10", 0).kind());
    }
}
