package com.modu.wereadlite;

import android.content.Intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅用于 UI 自测的假数据：启动时带 --ez demo true 才会生效，正常使用完全不走这里。
 * 目的是在没有账号的设备上校验书架版式（列宽、封面比例、标题两行、多选态）。
 */
final class Demo {

    private Demo() {
    }

    static boolean enabled(Intent intent) {
        return intent != null && intent.getBooleanExtra("demo", false);
    }

    static List<Map<String, Object>> books() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        out.add(book("1001", "福尔摩斯探案集（套装共8册）", 0, ""));
        out.add(book("1002", "蛤蟆先生去看心理医生", 0, "https://www.gstatic.com/webp/gallery/1.jpg"));
        out.add(book("1003", "认知觉醒：开启自我改变的原动力", 0, ""));
        out.add(book("1004", "三体全集（全三册）", 1, "https://www.gstatic.com/webp/gallery/2.jpg"));
        out.add(book("1005", "文章收藏", 0, ""));
        out.add(book("1006", "人类简史：从动物到上帝", 0, ""));
        out.add(book("1007", "被讨厌的勇气", 0, ""));
        out.add(book("1008", "置身事内：中国政府与经济发展", 0, ""));
        return out;
    }

    private static Map<String, Object> book(String id, String title, int secret, String cover) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("bookId", id);
        row.put("title", title);
        row.put("author", "demo");
        row.put("progress", 0);
        row.put("cover", cover);
        row.put("secret", secret);
        return row;
    }

    static List<Map<String, Object>> catalog() {
        String[] titles = {
                "血字的研究", "四签名", "第一章 歇洛克·福尔摩斯先生", "第二章 演绎法",
                "第三章 劳瑞斯顿花园街的惨案", "第四章 约翰·兰斯的叙述",
                "第五章 广告引来的不速之客", "第六章 托拜厄斯·格雷格森大显身手",
                "第七章 一线光明", "第八章 沙漠中的旅客", "第九章 犹他之花",
                "第十章 约翰·费瑞厄和先知的会谈", "第十一章 逃命", "第十二章 复仇天使",
        };
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < titles.length; i++) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("uid", "demo-" + i);
            row.put("title", titles[i]);
            row.put("level", i < 2 ? 1 : 2);
            row.put("wordCount", 3000 + i * 137);
            out.add(row);
        }
        return out;
    }

    static String text(String chapterTitle) {
        String[] paras = {
                "一八七八年，我在伦敦大学获得医学博士学位，随即前往内特黎进修军医的必修课程。课程修完之后，我被派往诺桑伯兰第五明火枪团担任军医助理。",
                "这个团当时驻扎在印度。在我还没有赶到部队以前，第二次阿富汗战役就爆发了。我在孟买上岸的时候，听说我所属的部队已经穿过山隘，向前挺进，深入敌境了。",
                "我只好跟着一群和我一样掉队的军官追赶部队，平安到达坎大哈以后，立即找到了自己的团，开始了我的新工作。",
                "我在那场战役中负了伤，一颗子弹打中了我的肩部，擦伤了锁骨，打断了大动脉。要不是我那忠实的勤务兵默里把我扶上马背，送回英军阵地，我早就落在敌人的手里了。",
                "伤痛使我憔悴不堪，加上长期辗转劳顿，我的身体变得极度虚弱，于是被送到后方医院。在医院里，我的健康渐渐恢复，可是身体仍然十分孱弱，医生建议我回英国休养。",
                "我在英国无亲无故，像空气一样自由，或者说像空气一样无依无靠。于是我很自然地来到伦敦这个吸收全国游手好闲之徒的大染缸。",
                "我在斯特兰大街的一家私人旅馆里住了一些时候，过着既不舒适又非常无聊的生活，钱一到手就花光，经济状况十分拮据。",
                "最后，我决定离开这家旅馆，另外找个便宜些的住处。就在我做出这个决定的当天，我在克莱梯利安酒吧门口遇见了小斯坦弗。他是我在巴茨时的一个助手。",
                "对于一个孤零零的人来说，能在伦敦的茫茫人海中遇见一张熟悉的面孔，确实是一件令人高兴的事。",
                "我们两人都很兴奋，我请他到霍尔本餐馆吃了一顿午饭。他问我近来在做什么，我简单地把自己的经历告诉了他。",
        };
        StringBuilder sb = new StringBuilder();
        sb.append("　　").append(chapterTitle).append("\n\n");
        for (int round = 0; round < 4; round++) {
            for (String p : paras) {
                sb.append("　　").append(p).append("\n\n");
            }
        }
        return sb.toString();
    }
}
