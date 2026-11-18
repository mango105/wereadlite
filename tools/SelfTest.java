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
        testBookmarks();
        testBackup();
        testSync();
        testEinkProgress();
        testReadingStats();
        testHighlights();
        testUnderlines();
        testHtmlRange();
        testBase64AndJsonWrite();
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

    // ---------- 书签 ----------

    static void testBookmarks() {
        Bookmarks.Item a = new Bookmarks.Item("u1", "第一章", 0, 120, "开头第一句", 1000L);
        Bookmarks.Item b = new Bookmarks.Item("u2", "第二章", 1, 30, "引号\"与换行\n都在", 2000L);
        java.util.List<Bookmarks.Item> items = new java.util.ArrayList<Bookmarks.Item>();
        items.add(a);
        items.add(b);
        String json = Bookmarks.encode(items);
        java.util.List<Bookmarks.Item> back = Bookmarks.decode(json);
        eq("书签往返条数", back.size(), 2);
        eq("书签往返 uid", back.get(0).uid, "u1");
        eq("书签往返偏移", back.get(0).off, 120);
        eq("书签往返时间", back.get(0).at, 1000L);
        eq("书签往返转义", back.get(1).excerpt, "引号\"与换行\n都在");
        eq("书签往返章节", back.get(1).chapter, "第二章");

        // 同一位置容差内去重，保留较新的摘录；不同章的书签照收
        java.util.List<Bookmarks.Item> local = new java.util.ArrayList<Bookmarks.Item>();
        local.add(new Bookmarks.Item("u1", "第一章", 0, 120, "旧摘录", 1000L));
        java.util.List<Bookmarks.Item> incoming = new java.util.ArrayList<Bookmarks.Item>();
        incoming.add(new Bookmarks.Item("u1", "第一章", 0, 128, "新摘录", 3000L));
        incoming.add(new Bookmarks.Item("u3", "第三章", 2, 10, "另一台设备", 4000L));
        java.util.List<Bookmarks.Item> merged = Bookmarks.merge(local, incoming);
        eq("合并去重后条数", merged.size(), 2);
        eq("合并保留较新摘录", merged.get(0).excerpt, "新摘录");
        eq("合并按章排序", merged.get(1).uid, "u3");

        java.util.List<Bookmarks.Item> far = new java.util.ArrayList<Bookmarks.Item>();
        far.add(new Bookmarks.Item("u1", "第一章", 0, 900, "", 1L));
        eq("容差外不去重", Bookmarks.merge(local, far).size(), 2);

        java.util.List<Bookmarks.Item> many = new java.util.ArrayList<Bookmarks.Item>();
        for (int i = 0; i < Bookmarks.MAX_PER_BOOK + 20; i++) {
            many.add(new Bookmarks.Item("u", "", 0, i * 100, "", i));
        }
        eq("书签上限", Bookmarks.merge(null, many).size(), Bookmarks.MAX_PER_BOOK);

        // 全量导出/导入：bookId 里带中文和引号也不能坏
        java.util.Map<String, String> one = new java.util.LinkedHashMap<String, String>();
        one.put("书\"A", json);
        String dump = Bookmarks.exportAll(one);
        java.util.Map<String, String> parsed = Bookmarks.parseAll(dump);
        eq("导出导入书本数", parsed.size(), 1);
        eq("导出导入后条数", Bookmarks.decode(parsed.get("书\"A")).size(), 2);
        eq("坏数据不崩", Bookmarks.parseAll("不是 json").size(), 0);
        eq("空数据不崩", Bookmarks.decode("").size(), 0);

        java.util.List<Bookmarks.Item> t = new java.util.ArrayList<Bookmarks.Item>();
        yes("首次加书签", Bookmarks.toggle(t, a));
        eq("加完条数", t.size(), 1);
        yes("同位置再点是取消", !Bookmarks.toggle(t,
                new Bookmarks.Item("u1", "", 0, 130, "", 1L)));
        eq("取消后条数", t.size(), 0);

        eq("摘录压平空白", Bookmarks.excerptOf("第一段\n\n  第二段\t第三段", 0), "第一段 第二段 第三段");
        eq("摘录越界不崩", Bookmarks.excerptOf("短", 999), "");
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
                "<?xml version='1.0'?><package version='2.0' unique-identifier='bid'>"
                        + "<metadata xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                        + "<dc:title>测试之书</dc:title><dc:creator>熊十力</dc:creator>"
                        + "<meta name='cover' content='coverimg'/>"
                        + "</metadata><manifest>"
                        + "<item id='cover' href='cover.xhtml' media-type='application/xhtml+xml'/>"
                        + "<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>"
                        + "<item id='tocx' href='toc.xhtml' media-type='application/xhtml+xml'/>"
                        + "<item id='vol1' href='text/vol1.xhtml' media-type='application/xhtml+xml'/>"
                        + "<item id='c1' href='text/ch1.xhtml' media-type='application/xhtml+xml'/>"
                        + "<item id='c2' href='text/../text/ch2.xhtml' media-type='application/xhtml+xml'/>"
                        + "<item id='coverimg' href='Images/cover.jpg' media-type='image/jpeg'"
                        + " properties='cover-image'/>"
                        + "</manifest><spine toc='ncx'>"
                        + "<itemref idref='cover'/><itemref idref='tocx'/>"
                        + "<itemref idref='vol1'/>"
                        + "<itemref idref='c1'/><itemref idref='c1'/><itemref idref='c2'/>"
                        + "</spine></package>");
        byte[] coverBytes = new byte[200];
        coverBytes[0] = (byte) 0xFF;
        coverBytes[1] = (byte) 0xD8;
        coverBytes[2] = (byte) 0xE0;
        put(z, "OEBPS/Images/cover.jpg", coverBytes);
        put(z, "OEBPS/toc.ncx",
                "<ncx><navMap>"
                        + "<navPoint id='n0' playOrder='1'><navLabel><text>总目录</text></navLabel>"
                        + "<content src='toc.xhtml'/></navPoint>"
                        + "<navPoint id='n1' playOrder='2'><navLabel><text>第一卷 卷名</text></navLabel>"
                        + "<content src='text/vol1.xhtml'/>"
                        + "<navPoint id='n1a' playOrder='3'><navLabel><text>第一章 浮世绘</text></navLabel>"
                        + "<content src='text/ch1.xhtml#frag'/></navPoint>"
                        + "</navPoint>"
                        + "<navPoint id='n2' playOrder='4'><navLabel><text>第二章 伦敦雨</text></navLabel>"
                        + "<content src='text/ch2.xhtml'/></navPoint>"
                        + "</navMap></ncx>");
        put(z, "OEBPS/cover.xhtml", "<html><head><title></title></head><body><img src='a.jpg'/></body></html>");
        put(z, "OEBPS/toc.xhtml",
                "<html><body><h1>目录</h1><div>第一章</div><div>第二章</div><div>第三章</div>"
                        + "<div>第四章</div><div>第五章</div></body></html>");
        put(z, "OEBPS/text/vol1.xhtml",
                "<html><body><p>第一卷 木兰歌</p><p>卷首题记也占一行。</p></body></html>");
        put(z, "OEBPS/text/ch1.xhtml",
                "<html><head><title>第一章 浮世绘</title></head><body><h1>第一章 浮世绘</h1>"
                        + "<p>这一章写江户街市的光影与人群，足够长以避开封面页判定。</p>"
                        + "<p>第二段同样留在本章之内。</p></body></html>");
        put(z, "OEBPS/text/ch2.xhtml",
                "<html><body><p>没有标题标签的一章，正文必须超过二十个字符才会被保留下来。</p></body></html>");
        z.close();

        Object bookObj = reflect("com.modu.wereadlite.LocalBooks", "parseEpub", epub);
        List<String[]> out = (List<String[]>) field(bookObj, "chapters");
        Object author = field(bookObj, "author");
        Object bookTitle = field(bookObj, "title");
        Object cover = field(bookObj, "cover");
        eq("EPUB 元数据书名", String.valueOf(bookTitle), "测试之书");
        eq("EPUB 元数据作者", String.valueOf(author), "熊十力");
        yes("EPUB 提取封面字节", cover != null && ((byte[]) cover).length > 16);
        eq("EPUB 跳过封面/重复", out.size(), 4);   // toc/卷首页保留为章节（官方目录也保留它们）
        eq("EPUB 目录页标题取 ncx", at(out, 0), "总目录");
        eq("EPUB 卷标题取 ncx", at(out, 1), "第一卷 卷名");
        eq("EPUB 章名取 ncx", at(out, 2), "第一章 浮世绘");
        eq("EPUB 正文去掉重复标题", bodyAt(out, 2),
                "这一章写江户街市的光影与人群，足够长以避开封面页判定。\n第二段同样留在本章之内。");
        yes("EPUB ../ 相对路径仍读到正文", out.size() > 3 && bodyAt(out, 3).length() > 10);
        List<String[]> nav = (List<String[]>) field(bookObj, "nav");
        eq("EPUB 目录条目数", nav.size(), 4);
        eq("EPUB 总目录归属", nav.get(0)[1], "0");
        eq("EPUB 卷归属", nav.get(1)[1], "1");
        eq("EPUB 卷深度", nav.get(1)[2], "1");
        eq("EPUB 子条目层级", at(nav, 2), "第一章 浮世绘");
        eq("EPUB 子条目归属章", nav.get(2)[1], "2");
        eq("EPUB 子条目深度", nav.get(2)[2], "2");
        eq("EPUB 目录指向保留章", nav.get(3)[1], "3");

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

    /** 读对象私有字段（测试专用）。 */
    static Object field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
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

    static void put(ZipOutputStream z, String path, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(path));
        z.write(data, 0, data.length);
        z.closeEntry();
    }

    // ---------- Backup 整包备份 ----------

    static void testBackup() throws Exception {
        // 会话/凭证键必须被排除，进度/书签/设置要保留
        yes("backup want lp", Backup.wantedPref("lp_local:123"));
        yes("backup want bm", Backup.wantedPref("bm_local:123"));
        yes("backup want rt", Backup.wantedPref("rt_local:123"));
        yes("backup want mk", Backup.wantedPref("mk_local:123"));
        yes("backup want fontSize", Backup.wantedPref("fontSize"));
        yes("backup want indent", Backup.wantedPref("indentCells"));
        yes("backup NO cookies", !Backup.wantedPref("cookies"));
        yes("backup NO skey", !Backup.wantedPref("skey"));
        yes("backup NO vid", !Backup.wantedPref("vid"));
        yes("backup NO einkCred", !Backup.wantedPref("einkCred"));
        yes("backup NO syncedAt", !Backup.wantedPref("syncedAt"));

        // 造两本假书
        File root = new File(System.getProperty("java.io.tmpdir"),
                "wl-backup-test-" + System.currentTimeMillis());
        File d1 = new File(root, "bk1");
        File d2 = new File(root, "bk2");
        d1.mkdirs(); d2.mkdirs();
        write(new File(d1, "meta.json"), "{\"title\":\"甲\"}".getBytes("UTF-8"));
        write(new File(d1, "ch0.txt"), "第一章正文甲乙丙丁".getBytes("UTF-8"));
        write(new File(d2, "meta.json"), "{\"title\":\"乙\"}".getBytes("UTF-8"));
        write(new File(d2, "ch0.txt"), "第二章正文戊己庚辛".getBytes("UTF-8"));

        java.util.Map<String, String> prefs = new java.util.LinkedHashMap<String, String>();
        prefs.put("fontSize", "24");
        prefs.put("indentCells", "6");
        prefs.put("lp_local:bk1", "3|120|1700000000000");
        prefs.put("bm_local:bk1", Bookmarks.encode(java.util.Arrays.asList(
                new Bookmarks.Item("0", "甲", 0, 12, "摘录", 1700000000000L))));

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        Backup.write(bos, root, prefs, "0.2.8", 1700000000000L);
        byte[] zip = bos.toByteArray();
        yes("backup zip bytes", zip.length > 100);

        // meta 可读
        java.util.Map<String, Object> meta = Backup.readMeta(
                new java.io.ByteArrayInputStream(zip));
        yes("backup meta format", Json.num(meta, "format") == 1);
        yes("backup meta books", Json.num(meta, "books") == 2);
        yes("backup meta prefs", Json.num(meta, "prefs") == 4);

        // prefs 可读，且会话没混进去
        java.util.Map<String, String> got = Backup.readPrefs(
                new java.io.ByteArrayInputStream(zip));
        yes("backup prefs fontSize", "24".equals(got.get("fontSize")));
        yes("backup prefs indent", "6".equals(got.get("indentCells")));
        yes("backup prefs lp", got.containsKey("lp_local:bk1"));
        yes("backup prefs bm", got.containsKey("bm_local:bk1"));
        yes("backup prefs no cookies", !got.containsKey("cookies"));

        // 书 key 可枚举
        java.util.Set<String> keys = Backup.readBookKeys(
                new java.io.ByteArrayInputStream(zip));
        yes("backup book keys size", keys.size() == 2);
        yes("backup book keys has bk1", keys.contains("bk1"));
        yes("backup book keys has bk2", keys.contains("bk2"));

        // 恢复：bk1 已存在要跳过，只写 bk2
        java.util.Set<String> existing = new java.util.HashSet<String>();
        existing.add("bk1");
        File dst = new File(root, "restore");
        dst.mkdirs();
        int written = Backup.restoreBooks(new java.io.ByteArrayInputStream(zip), dst, existing);
        yes("backup restore wrote bk2 files", written == 2);   // meta.json + ch0.txt
        yes("backup restore skipped bk1", !new File(dst, "bk1").exists());
        yes("backup restore has bk2", new File(dst, "bk2/ch0.txt").exists());
        String text = new String(readAllBytes(new File(dst, "bk2/ch0.txt")), "UTF-8");
        yes("backup restore content", text.contains("戊己庚辛"));

        // 目录穿越防护
        java.io.ByteArrayOutputStream evilBos = new java.io.ByteArrayOutputStream();
        ZipOutputStream ez = new ZipOutputStream(evilBos);
        put(ez, "books/../../evil.txt", "bad");
        ez.finish();
        File dst2 = new File(root, "evil");
        dst2.mkdirs();
        int evilWritten = Backup.restoreBooks(
                new java.io.ByteArrayInputStream(evilBos.toByteArray()), dst2,
                new java.util.HashSet<String>());
        yes("backup path traversal blocked", evilWritten == 0);

        deleteTree(root);
    }

    // ---------- 进度双向同步决策 ----------

    static void testSync() {
        // 目录：3 章，字数 1000 / 2000 / 1000，累计 0 / 1000 / 3000 / 4000
        List<Map<String, Object>> toc = new java.util.ArrayList<Map<String, Object>>();
        toc.add(syncChapter("10", 1000));
        toc.add(syncChapter("20", 2000));
        toc.add(syncChapter("30", 1000));
        Sync.Catalog cat = new Sync.Catalog(toc);
        eq("坐标表总字数", cat.total, 4000L);
        eq("坐标表按 uid 定位", cat.indexOf("20"), 1);
        eq("坐标表 uid 对不上", cat.indexOf("99"), -1);
        // 本地：第 1 章(index 1)读了一半，本章实际 2000 字 → (1000 + 1000)/4000 = 50%
        eq("本地坐标转百分比", round(cat.localPercent(1, 1000, 2000)), 50.0);
        // 云端：uid 20 章内偏移 1000 → (1000+1000)/4000 = 50%
        eq("云端坐标转百分比", round(cat.remotePercent("20", 1000)), 50.0);
        eq("云端 uid 对不上返回 -1", cat.remotePercent("99", 100), -1.0);
        eq("云端章内比例", round(cat.remoteChapterFraction("20", 1000)), 0.5);
        eq("云端偏移超出章字数被夹住", round(cat.remotePercent("20", 99999)), 75.0);

        // 空目录不抛，退化成 fall back 也不脏
        Sync.Catalog empty = new Sync.Catalog(new java.util.ArrayList<Map<String, Object>>());
        eq("空目录总字数 0", empty.total, 0L);
        eq("空目录百分比 0", empty.localPercent(0, 100, 1000), 0.0);

        // normalize：能用目录算就用算出来的，算不出退回原始 progress
        Sync.Pos raw = new Sync.Pos(63, "20", 1000, 100L, "web");
        eq("归一化按目录重算", round(Sync.normalizeRemote(cat, raw).percent), 50.0);
        Sync.Pos rawBad = new Sync.Pos(63, "99", 100, 100L, "web");
        eq("归一化 uid 对不上退回原值", Sync.normalizeRemote(cat, rawBad).percent, 63.0);

        // 时间戳规范化：毫秒折成秒
        eq("毫秒时间戳折成秒", Sync.normalizeTimestamp(1765000000000L), 1765000000L);
        eq("秒时间戳不变", Sync.normalizeTimestamp(1765000000L), 1765000000L);

        // chooseRemote：取更新时间新的；坐标分歧记为 disagreement（静默，不弹窗）
        Sync.Pos web = new Sync.Pos(50, "20", 1000, 100L, "web");
        Sync.Pos gwNew = new Sync.Pos(50, "20", 1005, 200L, "gateway");
        Sync.RemoteChoice c1 = Sync.chooseRemote(web, gwNew);
        eq("双源取更新的一方", c1.pos.source, "gateway");
        yes("双源偏移差 5 字不分歧", !c1.conflict);
        Sync.Pos gwFar = new Sync.Pos(70, "20", 1900, 200L, "gateway");
        yes("双源同章偏移差 900 标记分歧", Sync.chooseRemote(web, gwFar).conflict);
        Sync.Pos gwCross = new Sync.Pos(51, "30", 0, 200L, "gateway");
        yes("双源跨章标记分歧", Sync.chooseRemote(web, gwCross).conflict);
        eq("时间相同优先网页", Sync.chooseRemote(web, new Sync.Pos(50, "20", 1000, 100L, "gateway")).pos.source, "web");
        eq("只有网页源就用网页", Sync.chooseRemote(web, null).pos.source, "web");
        eq("只有网关源就用网关", Sync.chooseRemote(null, gwNew).pos.source, "gateway");

        // compare：坐标优先——同章偏移差在容差内算同位置，不管百分比差多少
        Sync.Pos local = new Sync.Pos(50, "20", 1000, 0L, "local");
        eq("同章偏移差 5 判同位置", Sync.compare(local, new Sync.Pos(55, "20", 1005, 0L, "web")), Sync.SAME);
        eq("同章偏移领先判云端领先", Sync.compare(local, new Sync.Pos(51, "20", 1200, 0L, "web")), Sync.REMOTE_AHEAD);
        eq("同章偏移落后判本地领先", Sync.compare(local, new Sync.Pos(49, "20", 800, 0L, "web")), Sync.LOCAL_AHEAD);
        eq("跨章差在阈值内判可疑",
                Sync.compare(local, new Sync.Pos(50.5, "30", 0, 0L, "web")), Sync.DIFFERENT_CHAPTER);
        eq("本地或云端为空按同位置", Sync.compare(null, local), Sync.SAME);
        // 只有一端有坐标时退回百分比
        Sync.Pos noCoord = new Sync.Pos(50, "", 0, 0L, "web");
        eq("无坐标按百分比同位置", Sync.compare(local, new Sync.Pos(51, "", 0, 0L, "web")), Sync.SAME);

        // decide：开关 > compare 结果；双源分歧不弹窗（已静默取新）
        eq("开书拉取关着就不动",
                Sync.decide(Sync.REMOTE_AHEAD, false, Sync.TRIGGER_OPEN, false, true).what, Sync.ACT_NONE);
        eq("关书上传关着就不动",
                Sync.decide(Sync.LOCAL_AHEAD, false, Sync.TRIGGER_CLOSE, true, false).what, Sync.ACT_NONE);
        eq("双源分歧不弹窗按同位置处理",
                Sync.decide(Sync.SAME, true, Sync.TRIGGER_OPEN, true, true).what, Sync.ACT_NONE);
        eq("两端一致不动",
                Sync.decide(Sync.SAME, false, Sync.TRIGGER_OPEN, true, true).what, Sync.ACT_NONE);
        eq("云端领先自动跳过去",
                Sync.decide(Sync.REMOTE_AHEAD, false, Sync.TRIGGER_OPEN, true, true).what, Sync.ACT_JUMP_REMOTE);
        eq("开书本地领先先保留",
                Sync.decide(Sync.LOCAL_AHEAD, false, Sync.TRIGGER_OPEN, true, true).what, Sync.ACT_KEEP_LOCAL);
        eq("关书本地领先回传云端",
                Sync.decide(Sync.LOCAL_AHEAD, false, Sync.TRIGGER_CLOSE, true, true).what, Sync.ACT_UPLOAD_LOCAL);
        eq("手动本地领先回传云端",
                Sync.decide(Sync.LOCAL_AHEAD, false, Sync.TRIGGER_MANUAL, true, true).what, Sync.ACT_UPLOAD_LOCAL);
        eq("手动跨章差异弹窗",
                Sync.decide(Sync.DIFFERENT_CHAPTER, false, Sync.TRIGGER_MANUAL, true, true).what, Sync.ACT_ASK);
        eq("手动同步不受开书开关限制",
                Sync.decide(Sync.REMOTE_AHEAD, false, Sync.TRIGGER_MANUAL, false, false).what, Sync.ACT_JUMP_REMOTE);
    }

    // ---------- E-Ink 精确进度解析 ----------

    static void testEinkProgress() {
        Eink.Progress p = Eink.parseProgress(
                "{\"bookId\":\"123\",\"chapterUid\":567,\"chapterOffset\":1234,"
                        + "\"progress\":42,\"updateTime\":1765000000,"
                        + "\"recordReadingTime\":7200,\"finishTime\":0}");
        eq("进度 uid 数字转字符串", p.chapterUid, "567");
        eq("进度章内偏移", p.chapterOffset, 1234);
        eq("进度百分比", p.percent, 42);
        eq("进度更新时间", p.updated, 1765000000L);
        eq("进度累计时长", p.readSeconds, 7200);
        yes("未读完", !p.finished);
        yes("有数据算有效", p.ok());

        // 毫秒时间戳在解析时折成秒
        Eink.Progress millis = Eink.parseProgress(
                "{\"chapterUid\":5,\"chapterOffset\":10,\"progress\":20,\"updateTime\":1765000000000}");
        eq("毫秒时间戳解析折秒", millis.updated, 1765000000L);

        // book 套一层
        Eink.Progress nested = Eink.parseProgress(
                "{\"book\":{\"chapterUid\":\"88\",\"chapterOffset\":50,\"progress\":7,"
                        + "\"updateTime\":100,\"finishTime\":123}}");
        eq("嵌套结构 uid", nested.chapterUid, "88");
        eq("嵌套结构百分比", nested.percent, 7);
        eq("嵌套结构更新时间", nested.updated, 100L);
        yes("读完标记", nested.finished);

        // 坏数据不抛
        eq("空体不抛", Eink.parseProgress("").ok(), false);
        eq("坏 JSON 不抛", Eink.parseProgress("not json").ok(), false);
        eq("缺字段默认有否", Eink.parseProgress("{}").ok(), false);
    }

    static Map<String, Object> syncChapter(String uid, int words) {
        Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("uid", uid);
        row.put("wordCount", Integer.valueOf(words));
        return row;
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static byte[] readAllBytes(File f) {
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] c = new byte[4096]; int n;
            while ((n = in.read(c)) > 0) b.write(c, 0, n);
            in.close();
            return b.toByteArray();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------- 阅读统计（我 / 页卡片） ----------

    static void testReadingStats() {
        FakePrefs p = new FakePrefs();
        Store s = new Store(p);

        // 累计 = 所有 rt_ 之和；负数/非 int 忽略
        eq("空书架累计为 0", s.totalReadTime(), 0L);
        p.put("rt_b1", 3600);
        p.put("rt_b2", 1800);
        p.put("rt_b3", -5);
        eq("累计时长求和", s.totalReadTime(), 5400L);

        // noteRead 同时累进本 App 累计 + 当天
        s.noteRead(600);
        s.noteRead(300);
        eq("本 App 累计", s.einkReadTime(), 900);
        s.noteRead(0);
        s.noteRead(-10);
        eq("无效应忽略", s.einkReadTime(), 900);

        // 本周：直接塞最近 8 天，只有 7 天内计入
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.Locale.CHINA);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA);
        for (int i = 0; i < 7; i++) {
            p.put("rd_" + f.format(c.getTime()), (i + 1) * 60);
            c.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
        c.add(java.util.Calendar.DAY_OF_MONTH, -1);   // 第 8 天
        String oldDay = "rd_" + f.format(c.getTime());
        p.put(oldDay, 9999);
        eq("本周 7 天求和", s.weekReadTime(), 60 + 120 + 180 + 240 + 300 + 360 + 420);
        yes("8 天前不计入本周", s.weekReadTime() < 9999);

        // 备份白名单
        yes("backup want rd_", Backup.wantedPref("rd_20261117"));
        yes("backup want einkRead", Backup.wantedPref("einkRead"));

        // 导入合并：int 按较大值，且不能写成 string
        FakePrefs p2 = new FakePrefs();
        Store s2 = new Store(p2);
        java.util.Map<String, String> inc = new java.util.LinkedHashMap<String, String>();
        inc.put("einkRead", "1234");
        inc.put("rd_20261117", "500");
        inc.put("rt_b1", "200");   // 目标端没有，应写成 int 200
        s2.mergeImportedPrefs(inc);
        eq("导入 einkRead 类型正确", s2.einkReadTime(), 1234);
        eq("导入 rd_ 类型正确", p2.getInt("rd_20261117", 0), 500);
        eq("导入 rt_ 类型正确", p2.getInt("rt_b1", -1), 200);
        // 再次导入更小值不覆盖
        inc.put("einkRead", "100");
        s2.mergeImportedPrefs(inc);
        eq("累计时长不倒退", s2.einkReadTime(), 1234);
    }

    static void testHighlights() {
        java.util.List<com.modu.wereadlite.Highlights.Item> items =
                new java.util.ArrayList<com.modu.wereadlite.Highlights.Item>();
        items = com.modu.wereadlite.Highlights.add(items,
                new com.modu.wereadlite.Highlights.Item("3", 1, 100, 120, "一百二十字的摘录", 1000L));
        items = com.modu.wereadlite.Highlights.add(items,
                new com.modu.wereadlite.Highlights.Item("3", 1, 40, 50, "第二段", 2000L));
        // 同区间重复添加只更新时间并保留同步状态
        com.modu.wereadlite.Highlights.Item dup =
                new com.modu.wereadlite.Highlights.Item("3", 1, 40, 50, "第二段", 3000L);
        dup.rid = "rid1";
        items = com.modu.wereadlite.Highlights.add(items, dup);
        eq("划线按偏移排序", items.get(0).start, 40);
        eq("划线去重计数", items.size(), 2);
        eq("划线时间被更新", items.get(0).at, 3000L);

        String enc = com.modu.wereadlite.Highlights.encode(items);
        java.util.List<com.modu.wereadlite.Highlights.Item> back =
                com.modu.wereadlite.Highlights.decode(enc);
        eq("划线编码往返", back.size(), 2);
        eq("划线往返 rid", back.get(0).rid, "rid1");
        eq("划线往返区间", back.get(0).start + "," + back.get(0).end, "40,50");

        // rid 已同步的条目再次 add 空 rid 时不丢同步状态
        com.modu.wereadlite.Highlights.Item again =
                new com.modu.wereadlite.Highlights.Item("3", 1, 40, 50, "第二段", 4000L);
        items = com.modu.wereadlite.Highlights.add(items, again);
        eq("重复添加保留 rid", items.get(0).rid, "rid1");

        eq("命中查找", com.modu.wereadlite.Highlights.at(items, 1, 45).start, 40);
        yes("区间外未命中", com.modu.wereadlite.Highlights.at(items, 1, 60) == null);
        yes("remove 删除", com.modu.wereadlite.Highlights.remove(items, "3", 40, 50));
        eq("删除后计数", items.size(), 1);
        eq("章过滤", com.modu.wereadlite.Highlights.ofChapter(items, 2).size(), 0);

        // Store 层：hl_ 前缀 + autoFlipSec 夹逼
        FakePrefs p = new FakePrefs();
        com.modu.wereadlite.Store s = new com.modu.wereadlite.Store(p);
        s.setHighlights("b1", enc);
        eq("store 划线计数", s.highlightCount("b1"), 2);
        s.setAutoFlipSec(999);
        eq("autoFlipSec 夹到 120", s.autoFlipSec(), 120);
        s.setAutoFlipSec(1);
        eq("autoFlipSec 夹到 10", s.autoFlipSec(), 10);
        yes("backup want hl_", com.modu.wereadlite.Backup.wantedPref("hl_b1"));

        // 导入合并：划线按区间去重
        FakePrefs p2 = new FakePrefs();
        com.modu.wereadlite.Store s2 = new com.modu.wereadlite.Store(p2);
        java.util.Map<String, String> inc = new java.util.LinkedHashMap<String, String>();
        inc.put("hl_b1", enc);
        s2.mergeImportedPrefs(inc);
        eq("划线导入合并", s2.highlightCount("b1"), 2);
    }

    static void testUnderlines() {
        String body = "{\"errCode\":0,\"data\":{\"underlines\":["
                + "{\"range\":\"383-415\",\"markText\":\"<p>这一章写江户街市</p>\",\"createTime\":1765000000},"
                + "{\"range\":\"100-120\",\"markText\":\"伦敦雨\",\"createTime\":\"1765000099\"}"
                + "]}}";
        java.util.List<java.util.Map<String, Object>> rows =
                com.modu.wereadlite.Eink.parseUnderlines(body);
        eq("underlines 顶层解析", rows.size(), 2);
        eq("markText 去标签", String.valueOf(rows.get(0).get("markText")), "这一章写江户街市");
        eq("range 保留", String.valueOf(rows.get(0).get("range")), "383-415");
        eq("时间字符串转 long", Long.parseLong(String.valueOf(rows.get(1).get("time"))), 1765000099L);

        // 顶层无 data 套壳
        String flat = "{\"underlines\":[{\"range\":\"1-2\",\"markText\":\"ab\"}]}";
        eq("underlines 扁平结构", com.modu.wereadlite.Eink.parseUnderlines(flat).size(), 1);
        // 空/坏 body
        eq("underlines 空 body", com.modu.wereadlite.Eink.parseUnderlines("").size(), 0);
        eq("underlines 坏 JSON", com.modu.wereadlite.Eink.parseUnderlines("{oops").size(), 0);
    }

    static void testHtmlRange() {
        // HTML 带标签与实体，正文是 htmlToText 的输出
        String html = "<html><body><p>这一章写江户街市的光影</p>"
                + "<p>第二段&nbsp;同样留&nbsp;在</p><p>第三段“雨”</p></body></html>";
        String text = com.modu.wereadlite.Protocol.htmlToText(html);
        eq("htmlToText 段落数", text.split("\n").length, 3);
        // 全文对齐：第一个词的 HTML 起点（<html><body><p> 共 15 个字符）
        int[] r = com.modu.wereadlite.Protocol.htmlRange(html, text, 0, 5);
        eq("起点落在正文区（跳过 <html><body><p>）", r == null ? -1 : r[0], 15);
        // 「江户街市」
        int at = text.indexOf("江户街市");
        r = com.modu.wereadlite.Protocol.htmlRange(html, text, at, at + 4);
        yes("中段映射成功", r != null && r[1] > r[0]);
        eq("中段映射切回原文", r == null ? "" : html.substring(r[0], r[1]), "江户街市");
        // 跨段选择（含 \n）
        int s = text.indexOf("第二段");
        int e = text.indexOf("第三段") + 3;
        r = com.modu.wereadlite.Protocol.htmlRange(html, text, s, e);
        yes("跨段选择映射成功", r != null && r[1] > r[0]);
        // 实体（&nbsp; → 空格）在正文中
        at = text.indexOf("同样留 在");
        yes("实体解码后正文存在", at > 0);
        // BOM 前缀被剥掉（与首个用例比较，不用被覆盖的 r）
        String bom = "\ufeff" + html;
        int[] first = com.modu.wereadlite.Protocol.htmlRange(html, text, 0, 5);
        int[] r2 = com.modu.wereadlite.Protocol.htmlRange(bom, text, 0, 5);
        eq("BOM 不影响起点", r2 != null && first != null ? r2[0] - first[0] : -1, 0);
        // 对齐不可能时失败降级
        yes("不匹配返回 null",
                com.modu.wereadlite.Protocol.htmlRange("<p>完全不同</p>", text, 0, 5) == null);
        yes("空 HTML 返回 null", com.modu.wereadlite.Protocol.htmlRange("", text, 0, 5) == null);
    }

    static void testBase64AndJsonWrite() {
        eq("b64 中文", com.modu.wereadlite.Protocol.b64("微读"), "5b6u6K+7");
        eq("b64 英文+中文", com.modu.wereadlite.Protocol.b64("Hello, 世界!"), "SGVsbG8sIOS4lueVjCE=");
        eq("b64 空", com.modu.wereadlite.Protocol.b64(""), "");
        eq("b64 纯 ASCII", com.modu.wereadlite.Protocol.b64("ab"), "YWI=");

        java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("bookId", "b1");
        m.put("chapterIdx", Integer.valueOf(3));
        m.put("type", Integer.valueOf(1));
        m.put("markText", "微\"读\"\n");
        String j = com.modu.wereadlite.Json.write(m);
        yes("Json.write 含转义", j.contains("微\\\"读\\\"\\n"));
        yes("Json.write 数字不加引号", j.contains("\"chapterIdx\":3"));
        // 往返解析
        java.util.Map<String, Object> back =
                com.modu.wereadlite.Json.asMap(com.modu.wereadlite.Json.parse(j));
        eq("Json.write 往返", com.modu.wereadlite.Json.str(back, "markText"), "微\"读\"\n");
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory()) deleteTree(k); else k.delete();
        }
        f.delete();
    }
}
