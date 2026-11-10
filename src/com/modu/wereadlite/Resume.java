package com.modu.wereadlite;

import java.util.List;
import java.util.Map;

/**
 * 续读位置的纯计算：解析云端记下的位置、在目录里定位章节、判断该接云端还是接本地。
 * 不碰 Android 运行时，SelfTest 里直接跑。
 */
final class Resume {

    /** 云端记下一次阅读位置用的编码："章节 uid|章内偏移|章节下标|云端更新时间(秒)"。 */
    static String encode(String uid, int offset, int idx, long updated) {
        return uid + "|" + Math.max(0, offset) + "|" + idx + "|" + updated;
    }

    /** 解不出来就当云端没记过。 */
    static Mark parse(String raw) {
        if (raw == null || raw.length() == 0) return null;
        String[] p = raw.split("\\|", -1);
        if (p.length < 4) return null;
        return new Mark(p[0], Store.parseInt(p[1]), Store.parseInt(p[2]), Store.parseLong(p[3]));
    }

    /** uid 在目录里的下标；目录对不上（章节增删、uid 变更）返回 -1。 */
    static int indexOf(List<Map<String, Object>> chapters, String uid) {
        if (uid == null || uid.length() == 0 || chapters == null) return -1;
        for (int i = 0; i < chapters.size(); i++) {
            Object v = chapters.get(i) == null ? null : chapters.get(i).get("uid");
            if (v != null && uid.equals(String.valueOf(v))) return i;
        }
        return -1;
    }

    /** 下标夹进目录范围，越界回 0。 */
    static int clamp(int idx, int size) {
        return idx < 0 || idx >= size ? 0 : idx;
    }

    /**
     * 谁读得更远：先比章节，再比章内偏移。
     * 不用时间戳比——设备时钟常和服务器差几分钟，而每次翻页我们都会把「章首位置」报上去，
     * 按时间挑会把本地读到的位置往回退。
     */
    static boolean ahead(int idx, int offset, int thanIdx, int thanOffset) {
        return idx > thanIdx || (idx == thanIdx && offset > thanOffset);
    }

    /** 云端记下的一次位置。uid 用来在目录里找章节，idx 只在 uid 对不上时兜底。 */
    static final class Mark {
        final String uid;
        final int offset;
        final int idx;
        final long updated;

        Mark(String uid, int offset, int idx, long updated) {
            this.uid = uid == null ? "" : uid;
            this.offset = Math.max(0, offset);
            this.idx = idx;
            this.updated = updated;
        }
    }

    private Resume() {
    }
}
