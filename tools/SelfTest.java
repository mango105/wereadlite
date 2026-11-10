package com.modu.wereadlite;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 纯 JVM 回归：只跑解析层（Json / Api.bookRow / Api.reviewRows / LocalBooks / BookRow 格式化），
 * 不碰网络和 Android 运行时。tools/ 不参与 build.sh，APK 里不会有这个类。
 */
public class SelfTest {

    static int passed;
    static int failed;

    public static void main(String[] args) throws Exception {
        testJson();
        testLog();
        testHtml();
        testShelfFault();
        testPager();
        testStyleLabel();
        testDetent();
        testReadTime();
        testResume();
        testBookRow();
        testRating();
        testReviewRows();
        testTxt();
        testEpub();
        System.out.println("---- " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---------- 断言 ----------

    static void eq(String name, Object actual, Object expected) {
        String a = String.valueOf(actual), b = String.valueOf(expected);
        if (a.equals(b)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  expect: " + b + "\n  actual: " + a);
        }
    }

    static void yes(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    /** 形如 "三体|刘慈欣|907|神作" 的紧凑断言，改字段名时一眼能看出错在哪。 */
    static String pick(Map<String, Object> row, String... keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append('|');
            Object v = row == null ? null : row.get(keys[i]);
            sb.append(v == null ? "<无>" : String.valueOf(v));
        }
        return sb.toString();
    }

    /** 越界返回标记而不是抛异常，方便一条失败不连带崩掉整组断言。 */
    static String at(List<String[]> rows, int i) {
        return i < rows.size() ? rows.get(i)[0] : "<越界 " + i + "/" + rows.size() + ">";
    }

    static String bodyAt(List<String[]> rows, int i) {
        return i < rows.size() ? rows.get(i)[1] : "<越界 " + i + "/" + rows.size() + ">";
    }

    static Object reflect(String cls, String method, Object arg) throws Exception {
        Class<?> c = Class.forName(cls);
        Method m = c.getDeclaredMethod(method, arg.getClass());
        m.setAccessible(true);
        return m.invoke(null, arg);
    }

    // ---------- Json ----------

    static void testJson() {
        Object root = Json.parse("{\"a\":{\"b\":{\"c\":[1,2,{\"k\":\"v\"}]}},\"n\":-7,\"f\":1.5,"
                + "\"t\":true,\"s\":\"文本\",\"nul\":null}");
        eq("Json 字符串", Json.str(root, "s"), "文本");
        eq("Json 负数", Json.num(root, "n"), -7);
        eq("Json 缺失 key", Json.num(root, "zzz"), 0);
        eq("Json 路径下钻", Json.str(Json.map(root, "a", "b"), "不存在"), "");
        eq("Json list 路径", Json.list(root, "a", "b", "c").size(), 3);
        eq("Json 数组元素", Json.str(Json.list(Json.parse("{\"a\":{\"b\":[{\"k\":\"v\"}]}}"),
                "a", "b").get(0), "k"), "v");
        eq("Json null 值取空串", Json.str(root, "nul"), "");
        eq("Json map 缺失返回空表", Json.map(root, "zzz").size(), 0);
        eq("Json 兄弟 key 依序回退", Json.str(Json.parse("{\"a\":1,\"b\":2}"), "a", "b"), "1");
        eq("Json 转义", Json.str(Json.parse("{\"s\":\"a\\\"b\\nc\\u4e2d\"}"), "s"), "a\"b\nc中");
        yes("Json 坏输入不抛异常", Json.parse("{oops") != null);
        eq("Json 顶层数组", Json.asList(Json.parse("[1,2,3]")).size(), 3);
    }

    // ---------- HTML ----------

    /** 诊断日志会落到 logcat 和外部目录，密钥必须打码。 */
    static void testLog() {
        eq("日志打码", Api.mask("wr_skey=FAKEKEY1; wr_vid=100000001; wr_rt=web%40mdai"),
                "wr_skey=**; wr_vid=**; wr_rt=**");
        eq("响应头里的 Set-Cookie 也打码",
                Api.mask("[Set-Cookie=[wr_skey=AbCd; Path=/]]"), "[Set-Cookie=[wr_skey=**; Path=/]]");
        eq("E-Ink 令牌打码", Api.mask("accessToken=XYZ123&vid=88"), "accessToken=**&vid=**");
        eq("无敏感字段原样", Api.mask("len=6051 ct=application/json"), "len=6051 ct=application/json");
    }

    static void testHtml() {
        eq("html 段落", Protocol.htmlToText("<p>一</p><p>二</p>"), "一\n二");
        eq("html 换行", Protocol.htmlToText("一<br>二<br/>三"), "一\n二\n三");
        eq("html 标签剥离", Protocol.htmlToText("<em>粗</em>体<span class=\"x\">细</span>"), "粗体细");
        eq("html 实体", Protocol.htmlToText("他&nbsp;说&ldquo;好&rdquo;&hellip;&mdash;&amp;lt;b&amp;gt;"),
                "他 说“好”…—<b>");
        eq("html 数字实体", Protocol.htmlToText("&#20013;文"), "中文");
        eq("html 十六进制实体", Protocol.htmlToText("&#x4e2d;文&#65292;好"), "中文，好");
        eq("html 实体换行", Protocol.htmlToText("甲&#10;乙"), "甲\n乙");
        eq("html 越界实体丢弃", Protocol.htmlToText("a&#999999;b"), "ab");
        eq("html 未知实体丢弃", Protocol.htmlToText("a&xyz;b"), "ab");
        eq("html 空白折叠", Protocol.htmlToText("  <div> 甲 </div>\n\n\n<div>乙</div>  "), "甲\n乙");
        eq("html 空", Protocol.htmlToText(null), "");
    }

    // ---------- 书架错误码 ----------

    /** 登录过期回的是空 books，不识别就会被当成「书架是空的」。 */
    static void testShelfFault() {
        eq("登录超时", Api.shelfFault("{\"errCode\":-2012,\"errMsg\":\"登录超时\",\"errLog\":\"C4Db5Vj\",\"info\":\"\"}"),
                "登录已过期，请重新登录");
        eq("登录失效", Api.shelfFault("{\"errCode\":-2010}"), "登录已过期，请重新登录");
        eq("正常书架不误报", Api.shelfFault("{\"books\":[{\"bookId\":\"a\"}],\"synckey\":123}"), null);
        eq("空书架不误报", Api.shelfFault("{\"books\":[],\"synckey\":123}"), null);
        eq("小写 errcode 也认", Api.shelfFault("{\"errcode\":-2012}"), "登录已过期，请重新登录");
        eq("其他错误带说明", Api.shelfFault("{\"errCode\":-2021,\"errMsg\":\"参数错误\"}"),
                "书架同步失败：参数错误");
        eq("坏返回体不误报", Api.shelfFault("{oops"), null);
    }

    // ---------- 分页 ----------

    /** 整齐文本：每行 lineH 高、10 个字符，切页位置能口算。返回 {lineStart, lineTop, lineBottom}。 */
    static int[][] uniform(int lines, int lineH) {
        int[] s = new int[lines];
        int[] t = new int[lines];
        int[] b = new int[lines];
        for (int i = 0; i < lines; i++) {
            s[i] = i * 10;
            t[i] = i * lineH;
            b[i] = (i + 1) * lineH;
        }
        return new int[][]{s, t, b};
    }

    static String pageOf(int[][] rows, int lines, int pageH) {
        int[] p = Pager.pageStarts(rows[0], rows[1], rows[2], lines, pageH);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) sb.append(p[i]).append(',');
        return sb.toString();
    }

    /**
     * 分页只由 Pager 这段纯计算决定，改坏这里等于整本书页码错乱：
     * 曾经每切一页都对剩下的全文重排一次，长章拖字号滑块会连续卡顿几十次。
     */
    static void testPager() {
        eq("每页 5 行切 3 页", pageOf(uniform(12, 20), 12, 100), "0,50,100,");
        eq("正好放满不追加空白页", pageOf(uniform(10, 20), 10, 200), "0,");
        eq("差 1px 就多一页", pageOf(uniform(10, 20), 10, 199), "0,90,");
        eq("每页 2 行", pageOf(uniform(12, 20), 12, 40), "0,20,40,60,80,100,");
        eq("一页正好一行", pageOf(uniform(3, 50), 3, 50), "0,10,20,");
        eq("单行比整页高也要前进", pageOf(uniform(3, 100), 3, 50), "0,10,20,");
        eq("一行文本不分页", pageOf(uniform(1, 20), 1, 100), "0,");
        eq("空章节", pageOf(uniform(0, 20), 0, 100), "0,");
        eq("页高为 0 不崩", pageOf(uniform(12, 20), 12, 0), "0,");
        int[] p = Pager.pageStarts(uniform(37, 13)[0], uniform(37, 13)[1], uniform(37, 13)[2], 37, 91);
        yes("页起始严格递增", increasing(p));
        eq("首页从 0 开始", p[0], 0);
        eq("最后一页不是空白页", p[p.length - 1] < 37 * 10, true);
    }

    static boolean increasing(int[] a) {
        for (int i = 1; i < a.length; i++) if (a[i] <= a[i - 1]) return false;
        return true;
    }

    // ---------- 显示样式读数 ----------

    /**
     * 滑杆档位换算。轻点只走一档、拖动吸附刻度，这套取整错了就是「一按字号翻倍」或「拖不到最大边距」。
     */
    static void testDetent() {
        eq("行距 123 吸到 124", Detent.snap(108, 200, 4, 123), 124);
        eq("行距 110 吸到 112", Detent.snap(108, 200, 4, 110), 112);
        eq("顶档不足一整步也给最大", Detent.snap(108, 200, 4, 199), 200);
        eq("低于最小值夹住", Detent.snap(108, 200, 4, 105), 108);
        eq("高于最大值夹住", Detent.snap(108, 200, 4, 250), 200);
        eq("边距 17 吸到 18", Detent.snap(4, 44, 2, 17), 18);
        eq("一档一步等于原值", Detent.snap(14, 34, 1, 21), 21);
        eq("零步长不崩", Detent.snap(14, 34, 0, 21), 21);
        eq("区间为点", Detent.snap(5, 5, 4, 9), 5);
        eq("起点比例", Detent.fromRatio(108, 200, 4, 0f), 108);
        eq("终点比例", Detent.fromRatio(108, 200, 4, 1f), 200);
        eq("负比例夹到起点", Detent.fromRatio(108, 200, 4, -0.5f), 108);
        eq("超界比例夹到终点", Detent.fromRatio(108, 200, 4, 2f), 200);
        eq("中点比例", Detent.fromRatio(108, 200, 4, 0.5f), 156);
        eq("轻点变大一档", Detent.nudge(108, 200, 4, 108, 1), 112);
        eq("轻点变小一档", Detent.nudge(108, 200, 4, 108, -1), 108);
        eq("顶端再点不变", Detent.nudge(108, 200, 4, 200, 1), 200);
        eq("非档位值先吸附", Detent.nudge(108, 200, 4, 123, 1), 128);
        eq("非档位值往下也吸附", Detent.nudge(108, 200, 4, 123, -1), 120);
        eq("字号逐档", Detent.nudge(14, 34, 1, 21, 1), 22);
        eq("字号顶档夹住", Detent.nudge(14, 34, 1, 34, 1), 34);
        int[] seen = new int[24];
        for (int i = 0; i < 24; i++) seen[i] = Detent.fromRatio(108, 200, 4, i / 23f);
        yes("整条轨道单调不减", monotonic(seen));
        yes("档位不会重复太多次", distinct(seen) >= 20);
    }

    static boolean monotonic(int[] a) {
        for (int i = 1; i < a.length; i++) if (a[i] < a[i - 1]) return false;
        return true;
    }

    static int distinct(int[] a) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            boolean once = true;
            for (int j = 0; j < i; j++) if (a[j] == a[i]) once = false;
            if (once) n++;
        }
        return n;
    }

    /** 行距读数手拼，地区设置把小数点换成逗号也不能跟着变。 */
    static void testStyleLabel() {
        eq("行距 1.08", Ui.lineSpacing(108), "1.08");
        eq("行距 1.23", Ui.lineSpacing(123), "1.23");
        eq("行距 1.00", Ui.lineSpacing(100), "1.00");
        eq("行距 2.00", Ui.lineSpacing(200), "2.00");
        eq("行距 1.90 补零", Ui.lineSpacing(190), "1.90");
    }

    /** Api.bookProgress 只往传进来的表里填，测试里包一层方便断言。 */
    static Map<String, String> marksFrom(String body) {
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        Api.bookProgress(body, out, null);
        return out;
    }

    static Map<String, Integer> timesFrom(String body) {
        Map<String, Integer> out = new java.util.LinkedHashMap<String, Integer>();
        Api.bookProgress(body, null, out);
        return out;
    }

    // ---------- 每本书阅读时长 ----------

    /** 时长和续读位置都在书架那一次返回里；没读过的书不能拿 0 盖掉本地攒下的时长。 */
    static void testReadTime() {
        Map<String, Integer> m = timesFrom(
                "{\"bookProgress\":[{\"bookId\":\"a\",\"readingTime\":5400},"
                        + "{\"bookId\":\"b\",\"readingTime\":59},{\"readingTime\":10},"
                        + "{\"bookId\":\"c\"},{\"bookId\":\"d\",\"readingTime\":3600},"
                        + "{\"bookId\":\"e\",\"readingTime\":0}]}");
        eq("跳过没有 bookId 的行", m.size(), 3);
        eq("没有 readingTime 不进表", String.valueOf(m.get("c")), "null");
        eq("读过 0 秒不进表", String.valueOf(m.get("e")), "null");
        eq("90 分钟", Ui.duration(m.get("a").intValue()), "1 小时 30 分");
        eq("59 秒", Ui.duration(m.get("b").intValue()), "不足 1 分钟");
        eq("整小时不带分", Ui.duration(m.get("d").intValue()), "1 小时");
        eq("取不到不显示", Ui.duration(-1), "");
        eq("60 秒", Ui.duration(60), "1 分钟");
        eq("3599 秒", Ui.duration(3599), "59 分钟");
        eq("没有 bookProgress", timesFrom("{}").size(), 0);
        eq("坏返回体不抛", timesFrom("not json at all").size(), 0);
        eq("空返回体不抛", timesFrom("").size(), 0);
    }

    // ---------- 续读位置 ----------

    static void testResume() {
        Map<String, String> marks = marksFrom(
                "{\"bookProgress\":["
                        + "{\"bookId\":\"31775266\",\"chapterUid\":12,\"chapterIdx\":11,"
                        + "\"chapterOffset\":2400,\"updateTime\":1765000000,\"readingTime\":5400},"
                        + "{\"bookId\":\"23720876\",\"chapterUid\":\"7\",\"chapterIdx\":6,"
                        + "\"chapterOffset\":0,\"updateTime\":1764000000},"
                        + "{\"chapterOffset\":99},"
                        + "{\"bookId\":\"x\"}]}");
        eq("跳过没有 bookId 的行", marks.size(), 3);
        eq("uid 是数字也转成字符串", marks.get("31775266"), "12|2400|11|1765000000");
        eq("uid 是字符串照样读", marks.get("23720876"), "7|0|6|1764000000");
        eq("缺字段按 0 记", marks.get("x"), "|0|0|0");
        eq("没有 bookProgress", marksFrom("{}").size(), 0);
        eq("坏返回体不抛", marksFrom("not json at all").size(), 0);
        eq("空返回体不抛", marksFrom("").size(), 0);


        Resume.Mark m = Resume.parse("12|2400|11|1765000000");
        eq("解析 uid", m.uid, "12");
        eq("解析章内偏移", m.offset, 2400);
        eq("解析章节下标", m.idx, 11);
        eq("解析云端时间", m.updated, 1765000000L);
        eq("空串当没记过", Resume.parse(""), null);
        eq("字段不足不接", Resume.parse("12|2400"), null);
        eq("乱码只当零", Resume.parse("a|b|c|d").offset, 0);
        eq("编码往负偏移夹回 0", Resume.encode("1", -5, 0, 0L), "1|0|0|0");

        List<Map<String, Object>> toc = new java.util.ArrayList<Map<String, Object>>();
        toc.add(tocRow("1"));
        toc.add(tocRow("7"));
        toc.add(tocRow("12"));
        eq("按 uid 定位章节", Resume.indexOf(toc, "12"), 2);
        eq("uid 对不上返回 -1", Resume.indexOf(toc, "99"), -1);
        eq("空目录不抛", Resume.indexOf(new java.util.ArrayList<Map<String, Object>>(), "1"), -1);
        eq("下标在范围内照用", Resume.clamp(2, 3), 2);
        eq("下标越界夹回 0", Resume.clamp(9, 3), 0);

        yes("云端章节更后就用云端", Resume.ahead(3, 0, 2, 5000));
        yes("同章节比偏移", Resume.ahead(2, 501, 2, 500));
        yes("同章节同偏移算没前进", !Resume.ahead(2, 500, 2, 500));
        yes("自己报上去的章首不盖本地", !Resume.ahead(2, 0, 2, 500));
        yes("云端在前面章节不往回跳", !Resume.ahead(1, 99999, 2, 0));
    }

    static Map<String, Object> tocRow(String uid) {
        Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("uid", uid);
        row.put("title", "第 " + uid + " 章");
        return row;
    }

    // ---------- 书籍行 ----------

    static void testBookRow() {
        Map<String, Object> wrapped = Api.bookRow(Json.parse(
                "{\"readingCount\":1234,\"bookInfo\":{\"bookId\":\"31775266\",\"title\":\"三体\","
                        + "\"author\":\"刘慈欣\",\"cover\":\"./cover/31775266.t2.jpg\",\"intro\":\"科幻\","
                        + "\"publisher\":\"重庆出版社\",\"wordCount\":884000,\"payType\":1,"
                        + "\"newRating\":907,\"newRatingCount\":277000,"
                        + "\"newRatingDetail\":{\"title\":\"神作\",\"good\":250000,\"mixed\":30000,\"poor\":1000}}}"));
        eq("bookInfo 包装", pick(wrapped, "bookId", "title", "author", "rating", "ratingTitle",
                "ratingCount", "good", "mixed", "poor", "readCount", "wordCount", "payType"),
                "31775266|三体|刘慈欣|907|神作|277000|250000|30000|1000|1234|884000|1");

        Map<String, Object> bare = Api.bookRow(Json.parse(
                "{\"bookId\":\"c1\",\"title\":\"活着\",\"authorName\":\"余华\",\"wordsCount\":98000,"
                        + "\"coverUrl\":\"http://cdn/1.jpg\",\"abstract\":\"农场故事\"}"));
        eq("裸对象别名兼容", pick(bare, "bookId", "title", "author", "cover", "intro", "wordCount", "rating"),
                "c1|活着|余华|http://cdn/1.jpg|农场故事|98000|<无>");

        Map<String, Object> book = Api.bookRow(Json.parse(
                "{\"book\":{\"bookId\":\"c2\",\"title\":\"无名呼叫\",\"newRating\":930}}"));
        eq("book 包装", pick(book, "bookId", "title", "rating"), "c2|无名呼叫|930");

        yes("公众号文章过滤", Api.bookRow(Json.parse("{\"bookId\":\"MP_WXS_20001\",\"title\":\"推文\"}")) == null);
        yes("无 bookId 过滤", Api.bookRow(Json.parse("{\"title\":\"没有 id\"}")) == null);
        yes("非对象入参容错", Api.bookRow(Json.parse("[1,2,3]")) == null);

        Map<String, Object> noRating = Api.bookRow(Json.parse("{\"bookId\":\"c3\",\"title\":\"无评分书\"}"));
        yes("无评分时不写 rating 键", !noRating.containsKey("rating"));
        eq("无评分书名回退", pick(noRating, "title", "author"), "无评分书|");

        // /web/book/info 的真实形状：平铺、没有 bookInfo 包装、字数叫 totalWords
        Map<String, Object> detail = Api.bookRow(Json.parse(
                "{\"bookId\":\"35551088\",\"title\":\"蛤蟆先生去看心理医生\",\"author\":\"罗伯特·戴博德\","
                        + "\"totalWords\":74972,\"format\":\"epub\",\"payType\":4097,"
                        + "\"publisher\":\"天津人民出版社\",\"newRating\":894,\"newRatingCount\":141095,"
                        + "\"newRatingDetail\":{\"title\":\"好评如潮\",\"good\":128000,\"poor\":1448}}"));
        eq("详情平铺结构", pick(detail, "title", "author", "wordCount", "payType", "publisher",
                "rating", "ratingCount", "ratingTitle", "good", "poor"),
                "蛤蟆先生去看心理医生|罗伯特·戴博德|74972|4097|天津人民出版社|894|141095|好评如潮|128000|1448");
    }

    static void testRating() {
        Map<String, Object> book = new java.util.LinkedHashMap<String, Object>();
        book.put("rating", Integer.valueOf(907));
        book.put("ratingTitle", "神作");
        eq("推荐值文案", BookRow.ratingText(book), "推荐值 90.7%（神作）");
        book.remove("ratingTitle");
        eq("推荐值无档位", BookRow.ratingText(book), "推荐值 90.7%");
        eq("零分不显示", BookRow.ratingText(new java.util.LinkedHashMap<String, Object>()), "");
        eq("千分制换算", BookRow.percent(930), "93.0");
        eq("千分制整数", BookRow.percent(1000), "100.0");
        eq("万单位", BookRow.wan(277000L), "27.7万");
        eq("整万不带小数", BookRow.wan(120000L), "12万");
        eq("不足一万原样", BookRow.wan(9999L), "9999");
        eq("字数万字", BookRow.words(884000), "88.4万字");
        eq("字数整万", BookRow.words(10000), "1万字");
        eq("字数小额", BookRow.words(9800), "9800字");
        eq("字数缺失", BookRow.words(0), "—");
        eq("str 兜底", BookRow.str(null), "");
        eq("num 兜底", BookRow.num(null), 0);
    }

    // ---------- 书友想法 ----------

    static void testReviewRows() {
        String eink = "{\"reviewsCount\":3,\"synckey\":100,\"reviews\":["
                + "{\"review\":{\"reviewId\":\"a1\",\"abstract\":\"划线原文\",\"content\":\"这是想法内容\","
                + "\"author\":{\"name\":\"张三\"},\"likeCount\":12,\"commentCount\":3,\"createTime\":1700000000}},"
                + "{\"reviewId\":\"a2\",\"content\":\"第二条\",\"authorName\":\"李四\","
                + "\"likes\":5,\"comments\":1,\"createTime\":1700000001},"
                + "{\"reviewId\":\"a3\",\"content\":\"\",\"abstract\":\"\"}]}";
        List<Map<String, Object>> rows = Api.reviewRows(eink);
        eq("想法条数（空内容丢弃）", rows.size(), 2);
        eq("想法内层包装", pick(rows.get(0), "author", "content", "mark", "like", "comment", "time"),
                "张三|这是想法内容|划线原文|12|3|1700000000");
        eq("想法外层字段", pick(rows.get(1), "author", "content", "like", "comment"), "李四|第二条|5|1");

        eq("网页会话空返回", Api.reviewRows("{}").size(), 0);
        eq("data 包装", Api.reviewRows(
                "{\"data\":{\"reviewList\":[{\"content\":\"包装里的想法\",\"author\":{\"name\":\"王五\"}}]}}").size(), 1);
        eq("errCode 返回", Api.reviewRows("{\"errcode\":-2010,\"errMsg\":\"需要登录\"}").size(), 0);
        eq("坏 JSON 容错", Api.reviewRows("not json at all").size(), 0);
        eq("空串容错", Api.reviewRows("").size(), 0);
        eq("想法 HTML 清洗", pick(Api.reviewRows(
                "{\"reviews\":[{\"content\":\"<p>第一段&nbsp;带<em>标签</em></p><p>第二段</p>\","
                        + "\"abstract\":\"<b>划线</b>原文\"}]}").get(0), "content", "mark"),
                "第一段 带标签\n第二段|划线原文");
        eq("想法缺作者", pick(Api.reviewRows("{\"reviews\":[{\"content\":\"没有作者\"}]}").get(0),
                "author", "content"), "|没有作者");
        eq("想法只有划线也算一条", Api.reviewRows(
                "{\"reviews\":[{\"abstract\":\"只有划线\",\"content\":\"\"}]}").size(), 1);
    }

    // ---------- 本地书 ----------

    static void testTxt() throws Exception {
        eq("本地 id 前缀", String.valueOf(LocalBooks.isLocal("local:123")), "true");
        eq("远端 id 不误判", String.valueOf(LocalBooks.isLocal("31775266")), "false");
        eq("null 不误判", String.valueOf(LocalBooks.isLocal(null)), "false");
        eq("支持 txt", String.valueOf(LocalBooks.supported("三体.TXT")), "true");
        eq("支持 epub 带空格", String.valueOf(LocalBooks.supported(" book.epub ")), "true");
        eq("拒绝 pdf", String.valueOf(LocalBooks.supported("x.pdf")), "false");
        eq("拒绝无扩展", String.valueOf(LocalBooks.supported("README")), "false");

        StringBuilder preface = new StringBuilder("前言");
        while (preface.length() <= 210) preface.append("序言正文，介绍成书背景。");

        String chapters = preface + "\n\n　第一章 起\n正文一。\n\n第二章 承\n正文二。\n"
                + "第一百零八章 转\n正文三。\n第三章\n\n\n第四章 合\n正文四。\n";
        File f = tmpFile("chapters.txt", chapters);
        List<String[]> out = (List<String[]>) reflect("com.modu.wereadlite.LocalBooks", "parseTxt", f);
        eq("章节切分数量（空标题章丢弃）", out.size(), 5);
        eq("超长前言单独成章", at(out, 0), "前言");
        eq("全角缩进章名", at(out, 1), "第一章 起");
        eq("章正文", bodyAt(out, 1), "正文一。");
        eq("中文数字章名", at(out, 3), "第一百零八章 转");
        eq("空标题后紧跟下一章", at(out, 4), "第四章 合");
        eq("末章正文", bodyAt(out, 4), "正文四。");

        String one = "第一章 只有一处标记\n" + pad(9000);
        List<String[]> single = (List<String[]>) reflect("com.modu.wereadlite.LocalBooks", "parseTxt",
                tmpFile("single.txt", one));
        eq("标记不足两处按长度切", single.size(), 2);
        eq("定长切分命名", at(single, 0), "第 1 节");

        String flat = pad(17000);
        List<String[]> none = (List<String[]>) reflect("com.modu.wereadlite.LocalBooks", "parseTxt",
                tmpFile("flat.txt", flat));
        eq("无章节按 8000 字分段", none.size(), 3);
        eq("短文本单段命名", at((List<String[]>) reflect("com.modu.wereadlite.LocalBooks", "parseTxt",
                tmpFile("short.txt", "一小段没有任何章节标记的文字。")), 0), "正文");
        eq("空文件零章", ((List<String[]>) reflect("com.modu.wereadlite.LocalBooks", "parseTxt",
                tmpFile("empty.txt", ""))).size(), 0);

        // 只有一处章节标记时按「无章节」处理，所以编码用例给两章
        String gbk = "第一章 编码\nGBK 中文正文测试。\n第二章 继续\n再一段 GBK 文字。\n";
        File gb = new File(dir(), "gbk.txt");
        write(gb, gbk.getBytes("GB18030"));
        List<String[]> gbkOut = (List<String[]>) reflect("com.modu.wereadlite.LocalBooks", "parseTxt", gb);
        eq("GBK 自动回退", gbkOut.size(), 2);
        eq("GBK 章名不乱码", at(gbkOut, 0), "第一章 编码");
        eq("GBK 正文不乱码", bodyAt(gbkOut, 0), "GBK 中文正文测试。");
        eq("GBK 第二章", bodyAt(gbkOut, 1), "再一段 GBK 文字。");

        byte[] bom = new byte[3 + "第一章 BOM\n正文甲。\n第二章 BOM2\n正文乙。".getBytes("UTF-8").length];
        System.arraycopy(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, 0, bom, 0, 3);
        System.arraycopy("第一章 BOM\n正文甲。\n第二章 BOM2\n正文乙。".getBytes("UTF-8"), 0, bom, 3,
                bom.length - 3);
        File bomFile = new File(dir(), "bom.txt");
        write(bomFile, bom);
        eq("UTF-8 BOM 剥离", at((List<String[]>) reflect("com.modu.wereadlite.LocalBooks", "parseTxt",
                bomFile), 0), "第一章 BOM");

        // 单处标记 -> 走定长切分，正文必须原样保留且不带 BOM
        String only = "第一章 唯一标记\n后面全是正文。";
        List<String[]> singleMark = (List<String[]>) reflect("com.modu.wereadlite.LocalBooks", "parseTxt",
                tmpFile("single-mark.txt", only));
        eq("单处标记按无章节处理", at(singleMark, 0), "正文");
        eq("单处标记正文不丢", bodyAt(singleMark, 0), only);
    }

    static String pad(int n) {
        StringBuilder sb = new StringBuilder(n + 8);
        String unit = "这一段是正文内容，用来把长度撑到指定字数以便验证定长切分。";
        while (sb.length() < n) sb.append(unit);
        return sb.toString();
    }

    static void testEpub() throws Exception {
        File epub = new File(dir(), "sample.epub");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(epub));
        put(z, "mimetype", "application/epub+zip");
        put(z, "META-INF/container.xml",
                "<?xml version='1.0'?><container version='1.0'><rootfiles>"
                        + "<rootfile full-path='OEBPS/content.opf' media-type='application/oebps-package+xml'/>"
                        + "</rootfiles></container>");
        put(z, "OEBPS/content.opf",
                "<?xml version='1.0'?><package version='2.0' unique-identifier='bid'><manifest>"
                        + "<item id='cover' href='cover.xhtml' media-type='application/xhtml+xml'/>"
                        + "<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>"
                        + "<item id='tocx' href='toc.xhtml' media-type='application/xhtml+xml'/>"
                        + "<item id='c1' href='text/ch1.xhtml' media-type='application/xhtml+xml'/>"
                        + "<item id='c2' href='text/../text/ch2.xhtml' media-type='application/xhtml+xml'/>"
                        + "</manifest><spine toc='ncx'>"
                        + "<itemref idref='cover'/><itemref idref='tocx'/>"
                        + "<itemref idref='c1'/><itemref idref='c1'/><itemref idref='c2'/>"
                        + "</spine></package>");
        put(z, "OEBPS/toc.ncx",
                "<ncx><navMap>"
                        + "<navPoint id='n1' playOrder='1'><navLabel><text>第一章 浮世绘</text></navLabel>"
                        + "<content src='text/ch1.xhtml#frag'/></navPoint>"
                        + "<navPoint id='n2' playOrder='2'><navLabel><text>第二章 伦敦雨</text></navLabel>"
                        + "<content src='text/ch2.xhtml'/></navPoint>"
                        + "</navMap></ncx>");
        put(z, "OEBPS/cover.xhtml", "<html><head><title></title></head><body><img src='a.jpg'/></body></html>");
        put(z, "OEBPS/toc.xhtml",
                "<html><body><h1>目录</h1><div>第一章</div><div>第二章</div><div>第三章</div>"
                        + "<div>第四章</div><div>第五章</div></body></html>");
        put(z, "OEBPS/text/ch1.xhtml",
                "<html><head><title>第一章 浮世绘</title></head><body><h1>第一章 浮世绘</h1>"
                        + "<p>这一章写江户街市的光影与人群，足够长以避开封面页判定。</p>"
                        + "<p>第二段同样留在本章之内。</p></body></html>");
        put(z, "OEBPS/text/ch2.xhtml",
                "<html><body><p>没有标题标签的一章，正文必须超过二十个字符才会被保留下来。</p></body></html>");
        z.close();

        List<String[]> out = (List<String[]>) reflect("com.modu.wereadlite.LocalBooks", "parseEpub", epub);
        eq("EPUB 跳过封面/目录/重复", out.size(), 2);
        eq("EPUB 章名取 ncx", at(out, 0), "第一章 浮世绘");
        eq("EPUB 正文去掉重复标题", bodyAt(out, 0),
                "这一章写江户街市的光影与人群，足够长以避开封面页判定。\n第二段同样留在本章之内。");
        yes("EPUB ../ 相对路径仍读到正文", out.size() > 1 && bodyAt(out, 1).length() > 10);
        yes("EPUB 无标题时兜底命名", at(out, 1).startsWith("第 "));

        File bad = new File(dir(), "bad.epub");
        ZipOutputStream b = new ZipOutputStream(new FileOutputStream(bad));
        put(b, "mimetype", "application/epub+zip");
        b.close();
        boolean threw = false;
        try {
            reflect("com.modu.wereadlite.LocalBooks", "parseEpub", bad);
        } catch (Exception e) {
            threw = true;
        }
        yes("缺 container.xml 明确报错", threw);
    }

    // ---------- 临时文件 ----------

    static File dir() {
        File d = new File(System.getProperty("java.io.tmpdir"), "wereadlite-selftest");
        d.mkdirs();
        return d;
    }

    static File tmpFile(String name, String text) {
        File f = new File(dir(), name);
        write(f, text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        return f;
    }

    static void write(File f, byte[] bytes) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void put(ZipOutputStream z, String path, String text) throws Exception {
        z.putNextEntry(new ZipEntry(path));
        byte[] b = text.getBytes("UTF-8");
        z.write(b, 0, b.length);
        z.closeEntry();
    }
}
