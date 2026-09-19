package com.yuyutian.mytools.reader.model.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;

import java.util.UUID;

/** 只读上下文片段，摘要和长度始终由服务端对实际文本计算。 */
public record AdaptationContextFragment(AdaptationContextRole role, UUID sourceChapterId, String text) {

    /** 验证片段类型、章节关联与硬上限，不截断目标正文。 */
    public AdaptationContextFragment {
        if (role == null) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
        boolean chapterText = switch (role) {
            case TARGET_ORIGINAL, BASE_INPUT, PREVIOUS_TAIL, NEXT_HEAD -> true;
            default -> false;
        };
        if (chapterText != (sourceChapterId != null)) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
        int maximum = switch (role) {
            case TARGET_ORIGINAL, BASE_INPUT -> 120000;
            case PREVIOUS_TAIL, NEXT_HEAD -> 4000;
            case CATALOG_METADATA -> 16000;
            default -> 32;
        };
        AdaptationText.requireText(text, 1, maximum, ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
        if (text.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
        // 边界标记只使用固定值，防止把正文藏入不带章节身份的角色。
        if ((role == AdaptationContextRole.BOOK_START_MARKER && !"BOOK_START".equals(text))
                || (role == AdaptationContextRole.BOOK_END_MARKER && !"BOOK_END".equals(text))) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
    }

    /** 返回原样 UTF-8 文本的摘要。 */
    public String contentSha256() {
        return AdaptationText.sha256(text);
    }

    /** 返回 Unicode 码点长度，不把补充平面字符计为两个字符。 */
    public int codepointCount() {
        return text.codePointCount(0, text.length());
    }

    /** 避免日志输出小说正文。 */
    @Override
    public String toString() {
        return "AdaptationContextFragment[role=" + role + ", content=redacted]";
    }
}
