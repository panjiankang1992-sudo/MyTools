package com.yuyutian.mytools.toolbox.service.translation;

import com.yuyutian.mytools.toolbox.model.CurriculumEntry;
import com.yuyutian.mytools.toolbox.model.ListType;
import com.yuyutian.mytools.toolbox.model.PinyinSegment;
import com.yuyutian.mytools.toolbox.model.ReviewStatus;
import com.yuyutian.mytools.toolbox.model.Subject;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 已发布教材优先的双向精确词典和拼音索引，不访问草稿或外部服务。 */
@Component
public class TranslationDictionary {

    /** 双向词条。 */
    public record Pair(String englishText, String meaning) {
    }

    /** 同一代的所有索引必须一次替换，读取时不出现半成品或混代。 */
    private record Index(long generation, Map<String, Pair> exact, Map<String, Pair> aliases,
                         Map<String, List<PinyinSegment>> pinyin) {
    }

    private static final Pattern SYLLABLE = Pattern.compile("[a-zA-ZüÜāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜńňǹḿê]+");
    private final CurriculumCatalog catalog;
    private final Map<String, Pair> common;
    private volatile Index index = new Index(-1, Map.of(), Map.of(), Map.of());
    private final Object rebuildLock = new Object();

    /**
     * 从已发布教材快照建立索引，并保留常用词作为次级回退。
     *
     * @param catalog 已发布教材目录
     */
    public TranslationDictionary(CurriculumCatalog catalog) {
        this.catalog = catalog;
        Map<String, Pair> defaults = new HashMap<>();
        addCommon(defaults);
        common = Map.copyOf(defaults);
        refreshIfStale();
    }

    /** 只在目录换代时构建，整次构建从一个不可变快照读取。 */
    private void refreshIfStale() {
        if (index.generation() == catalog.snapshotVersion()) {
            return;
        }
        synchronized (rebuildLock) {
            if (index.generation() == catalog.snapshotVersion()) {
                return;
            }
            var snapshot = catalog.dictionarySnapshot();
            var books = new ArrayList<>(snapshot.books());
            // 同词有多条教材释义时优先最近发布的教材，时间相同仍保持确定顺序。
            books.sort(Comparator
                    .comparing((CurriculumCatalog.DictionaryBook book) -> book.edition().publishedAt(),
                            Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                    .thenComparing(book -> book.edition().editionId())
                    .thenComparing(book -> book.edition().contentVersion()));
            Map<String, Pair> exact = new HashMap<>();
            Map<String, Pair> aliases = new HashMap<>();
            Map<String, List<PinyinSegment>> pinyin = new HashMap<>();
            for (var book : books) {
                var entries = new ArrayList<>(book.entries());
                entries.sort(Comparator.comparingInt(CurriculumEntry::order).thenComparing(CurriculumEntry::entryId));
                for (var entry : entries) {
                    // 未校对、阻断或正文为空的条目不能成为公开补全来源。
                    if (!usable(entry)) {
                        continue;
                    }
                    if (book.edition().subject() == Subject.CHINESE) {
                        List<PinyinSegment> segments = alignedPinyin(entry);
                        if (!segments.isEmpty()) {
                            pinyin.putIfAbsent(key(entry.text()), segments);
                        }
                    } else if (entry.listTypes().contains(ListType.VOCABULARY)
                            || entry.listTypes().contains(ListType.EXPRESSION)) {
                        Pair pair = new Pair(entry.text().strip(), entry.meaning() == null ? "" : entry.meaning().strip());
                        if (!safePair(pair)) {
                            continue;
                        }
                        exact.putIfAbsent(key(pair.englishText()), pair);
                        exact.putIfAbsent(key(pair.meaning()), pair);
                        for (String alias : meaningAliases(pair.meaning())) {
                            aliases.putIfAbsent(key(alias), pair);
                        }
                    }
                }
            }
            index = new Index(snapshot.generation(), Map.copyOf(exact), Map.copyOf(aliases), Map.copyOf(pinyin));
        }
    }

    /**
     * 当前词典代际，供外部翻译缓存检测发布后的失效。
     *
     * @return 词典所依据的目录代际
     */
    public long snapshotVersion() {
        refreshIfStale();
        return index.generation();
    }

    /**
     * 优先完整教材词条，其次独立中文义项，最后使用常用词表。
     *
     * @param text 输入正文
     * @return 本地词条；不会按子串或逐词拼装答案
     */
    public Optional<Pair> find(String text) {
        refreshIfStale();
        Index current = index;
        String lookup = key(text);
        Pair match = current.exact().get(lookup);
        if (match == null) {
            match = current.aliases().get(lookup);
        }
        return Optional.ofNullable(match == null ? common.get(lookup) : match);
    }

    /**
     * 精确查询已发布教材的整词拼音，不把不同词的读音拼接起来。
     *
     * @param text 汉字正文
     * @return 与正文逐字对齐的教材拼音
     */
    public Optional<List<PinyinSegment>> findPinyin(String text) {
        refreshIfStale();
        return Optional.ofNullable(index.pinyin().get(key(text)));
    }

    /** 统一组合字符、空白与大小写；原文回显仍由调用方保留。 */
    static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean usable(CurriculumEntry entry) {
        return entry.text() != null && !entry.text().isBlank() && entry.blockingIssues().isEmpty()
                && (entry.reviewStatus() == ReviewStatus.REVIEWED || entry.reviewStatus() == ReviewStatus.PUBLISHED);
    }

    private static boolean containsScript(String text, Character.UnicodeScript script) {
        return text.codePoints().anyMatch(point -> Character.UnicodeScript.of(point) == script);
    }

    private static boolean safePair(Pair pair) {
        return containsScript(pair.englishText(), Character.UnicodeScript.LATIN)
                && !containsScript(pair.englishText(), Character.UnicodeScript.HAN)
                && containsScript(pair.meaning(), Character.UnicodeScript.HAN)
                && !containsScript(pair.meaning(), Character.UnicodeScript.LATIN);
    }

    /** 只分隔括号外的独立义项，保留教材原释义与括号内的上下文。 */
    private static List<String> meaningAliases(String meaning) {
        List<String> aliases = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int offset = 0; offset < meaning.length(); offset++) {
            char character = meaning.charAt(offset);
            if ("(\uff08[\u3010".indexOf(character) >= 0) {
                depth++;
            } else if (")\uff09]\u3011".indexOf(character) >= 0) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && "\uff0c,\uff1b;\u3001".indexOf(character) >= 0) {
                addAlias(aliases, meaning.substring(start, offset));
                start = offset + 1;
            }
        }
        addAlias(aliases, meaning.substring(start));
        return aliases;
    }

    private static void addAlias(List<String> aliases, String text) {
        String alias = text.strip();
        if (!alias.isEmpty() && containsScript(alias, Character.UnicodeScript.HAN)
                && !containsScript(alias, Character.UnicodeScript.LATIN)) {
            aliases.add(alias);
        }
    }

    /** 只采用完整且对齐的教材读音，缺音、非汉字和错位数据交给后续渠道。 */
    private static List<PinyinSegment> alignedPinyin(CurriculumEntry entry) {
        String text = Normalizer.normalize(entry.text().strip(), Normalizer.Form.NFC);
        List<String> graphemes = text.codePoints().mapToObj(point -> new String(Character.toChars(point))).toList();
        if (graphemes.isEmpty() || text.codePoints().anyMatch(point -> Character.UnicodeScript.of(point) != Character.UnicodeScript.HAN)) {
            return List.of();
        }
        List<PinyinSegment> segments = entry.pinyinSegments();
        // 老教材可能仅有展示字段；只有音节数严格对齐时才能生成片段。
        if (segments.isEmpty() && entry.pinyinDisplay() != null && !entry.pinyinDisplay().isBlank()) {
            String[] syllables = entry.pinyinDisplay().strip().split("\\s+");
            if (syllables.length != graphemes.size()) {
                return List.of();
            }
            segments = new ArrayList<>();
            for (int position = 0; position < syllables.length; position++) {
                segments.add(new PinyinSegment(graphemes.get(position), syllables[position]));
            }
        }
        if (segments.size() != graphemes.size()) {
            return List.of();
        }
        List<PinyinSegment> result = new ArrayList<>();
        for (int position = 0; position < graphemes.size(); position++) {
            PinyinSegment segment = segments.get(position);
            if (segment == null || !graphemes.get(position).equals(segment.grapheme()) || segment.syllable() == null) {
                return List.of();
            }
            String syllable = Normalizer.normalize(segment.syllable().strip(), Normalizer.Form.NFC);
            if (!SYLLABLE.matcher(syllable).matches()) {
                return List.of();
            }
            result.add(new PinyinSegment(segment.grapheme(), syllable));
        }
        return List.copyOf(result);
    }

    private void add(Map<String, Pair> target, String english, String chinese, String... aliases) {
        Pair pair = new Pair(english, chinese);
        target.put(key(english), pair);
        target.put(key(chinese), pair);
        for (String alias : aliases) {
            target.put(key(alias), pair);
        }
    }

    private void addCommon(Map<String, Pair> target) {
        String[] weekdays = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
        String[] dayNumbers = {"\u4e00", "\u4e8c", "\u4e09", "\u56db", "\u4e94", "\u516d", "\u65e5"};
        for (int index = 0; index < weekdays.length; index++) {
            String number = dayNumbers[index];
            add(target, weekdays[index], "\u661f\u671f" + number, "\u5468" + number, "\u793c\u62dc" + number);
        }
        add(target, "Sunday", "\u661f\u671f\u65e5", "\u661f\u671f\u5929", "\u5468\u5929", "\u793c\u62dc\u5929");
        String[] months = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
        String[] digits = {"\u96f6", "\u4e00", "\u4e8c", "\u4e09", "\u56db", "\u4e94", "\u516d", "\u4e03", "\u516b", "\u4e5d"};
        for (int index = 1; index <= months.length; index++) {
            String month = index < 10 ? digits[index] : "\u5341" + (index == 10 ? "" : digits[index - 10]);
            add(target, months[index - 1], month + "\u6708", index + "\u6708");
        }
        String[] smallNumbers = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        for (int index = 0; index < smallNumbers.length; index++) {
            String number = index < 10 ? digits[index] : "\u5341" + (index == 10 ? "" : digits[index - 10]);
            add(target, smallNumbers[index], number, Integer.toString(index));
        }
        String[] tens = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int index = 0; index < tens.length; index++) {
            add(target, tens[index], digits[index + 2] + "\u5341", Integer.toString((index + 2) * 10));
        }
        add(target, "one hundred", "\u4e00\u767e", "hundred", "100");
    }
}
