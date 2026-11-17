package com.modu.wereadlite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阅读进度双向同步的决策核心：字数坐标映射 + 双云端源取舍 + 冲突判定。
 *
 * 对照 weread.koplugin 的 position_mapper.lua / progress_sync.lua 移植，
 * 但只保留与本项目相关的纯计算部分——不碰 Android、不碰网络、不碰存储，
 * SelfTest 里直接跑。
 *
 * 设计要点：
 * - 两端坐标不同（本地是「第几页」，云端是「第几章 + 章内字数偏移」），
 *   统一折算成全书百分比再比，折算依据是目录里每章 wordCount 的累计权重；
 * - 差异 ≤2% 视为同一位置，减少无谓打扰；
 * - 两个云端源（网页 / E-Ink）取更新时间新的，差距过大或跨章超阈值标记冲突；
 * - 云端领先自动跟随、本地领先保留（关书/手动时回传）、真冲突交给用户裁决。
 */
final class Sync {

    /** 百分比差异不超过这个值就当成"同一位置"。 */
    static final double SAME_THRESHOLD = 2.0;
    /** 章节不同且差异超过这个值，才算两个云端源互相冲突。 */
    static final double CROSS_CHAPTER_GAP = 0.5;
    /** 同章章内偏移差不超过这个字数，视为同一位置（native co 可放宽到 16）。 */
    static final int OFFSET_TOLERANCE = 12;

    // 弱网重试参数（调度层用，这里集中定义便于测试与调整）
    static final long PULL_RETRY_MS = 15000L;
    static final int PULL_RETRY_MAX = 3;
    static final long PUSH_RETRY_MS = 2000L;
    static final int PUSH_RETRY_MAX = 10;

    // 触发时机
    static final int TRIGGER_OPEN = 0;
    static final int TRIGGER_CLOSE = 1;
    static final int TRIGGER_MANUAL = 2;

    // compare 结果
    static final int SAME = 0;
    static final int REMOTE_AHEAD = 1;
    static final int LOCAL_AHEAD = 2;
    static final int DIFFERENT_CHAPTER = 3;

    // decide 动作
    static final int ACT_NONE = 0;
    static final int ACT_JUMP_REMOTE = 1;
    static final int ACT_KEEP_LOCAL = 2;
    static final int ACT_UPLOAD_LOCAL = 3;
    static final int ACT_ASK = 4;

    /** 统一坐标里的一个位置。percent 0~100；source: local / web / gateway。 */
    static final class Pos {
        final double percent;
        final String chapterUid;
        final int chapterOffset;
        final long updated;
        final String source;

        Pos(double percent, String chapterUid, int chapterOffset, long updated, String source) {
            this.percent = clamp(percent, 0, 100);
            this.chapterUid = chapterUid == null ? "" : chapterUid;
            this.chapterOffset = Math.max(0, chapterOffset);
            this.updated = updated;
            this.source = source == null ? "" : source;
        }
    }

    /** 两个云端源的取舍结果。disagreement 只是诊断信息——源分歧一律静默取新，不等于用户级冲突。 */
    static final class RemoteChoice {
        final Pos pos;
        final boolean conflict;       // 兼容旧调用；语义同 disagreement

        RemoteChoice(Pos pos, boolean conflict) {
            this.pos = pos;
            this.conflict = conflict;
        }
    }

    /** 最终动作 + 一句说明（写日志 / 提示用）。 */
    static final class Action {
        final int what;
        final String reason;

        Action(int what, String reason) {
            this.what = what;
            this.reason = reason;
        }
    }

    /**
     * 由目录构建的字数坐标表。每章权重取 wordCount，缺失时按有字数章节的平均值兜底，
     * 与 Progress 的算法一致，保证本地百分比和云端百分比落在同一把尺子上。
     */
    static final class Catalog {
        final int[] words;
        final long[] before;
        final long total;
        final Map<String, Integer> byUid;
        final int fallback;

        Catalog(List<Map<String, Object>> chapters) {
            int n = chapters == null ? 0 : chapters.size();
            words = new int[n];
            before = new long[n + 1];
            byUid = new LinkedHashMap<String, Integer>();
            long counted = 0;
            int countedN = 0;
            for (int i = 0; i < n; i++) {
                Map<String, Object> row = chapters.get(i);
                int w = 0;
                if (row != null) {
                    Object wc = row.get("wordCount");
                    if (wc instanceof Number) w = ((Number) wc).intValue();
                    if (w < 0) w = 0;
                    if (w > 50) {
                        counted += w;
                        countedN++;
                    }
                    Object u = row.get("uid");
                    if (u != null && String.valueOf(u).length() > 0
                            && !byUid.containsKey(String.valueOf(u))) {
                        byUid.put(String.valueOf(u), Integer.valueOf(i));
                    }
                }
                words[i] = w;
            }
            fallback = countedN == 0 ? 1000 : Math.max(200, (int) (counted / countedN));
            for (int i = 0; i < n; i++) before[i + 1] = before[i] + weight(i);
            total = before[n];
        }

        private int weight(int i) {
            return words[i] > 0 ? words[i] : fallback;
        }

        int indexOf(String uid) {
            if (!uidKnown(uid)) return -1;
            Integer v = byUid.get(uid);
            return v == null ? -1 : v.intValue();
        }

        /** 本地位置（章 idx + 章内字数偏移 + 本章实际字数）→ 全书百分比。 */
        double localPercent(int idx, int offset, int chapterLen) {
            if (total <= 0 || idx < 0 || idx >= words.length) return 0;
            double frac = chapterLen > 0 ? (double) offset / chapterLen : 0;
            return clamp((before[idx] + frac * weight(idx)) / total * 100.0, 0, 100);
        }

        /** 云端位置（uid + 章内字数偏移）→ 全书百分比；uid 对不上返回 -1（不可安全映射）。 */
        double remotePercent(String uid, int chapterOffset) {
            int idx = indexOf(uid);
            if (idx < 0 || total <= 0) return -1;
            int w = weight(idx);
            int off = Math.min(Math.max(0, chapterOffset), w);
            return clamp((double) (before[idx] + off) / total * 100.0, 0, 100);
        }

        /** 云端位置落在哪一章的章内比例（0~1）；uid 对不上或章无字数返回 -1。 */
        double remoteChapterFraction(String uid, int chapterOffset) {
            int idx = indexOf(uid);
            if (idx < 0) return -1;
            int w = weight(idx);
            if (w <= 0) return -1;
            return clamp((double) Math.min(Math.max(0, chapterOffset), w) / w, 0, 1);
        }
    }

    /** 把云端原始位置按字数目录重算百分比；算不出来就退回它自带的 progress。 */
    static Pos normalizeRemote(Catalog cat, Pos raw) {
        if (cat == null || raw == null) return raw;
        double p = cat.remotePercent(raw.chapterUid, raw.chapterOffset);
        if (p < 0) {
            return new Pos(raw.percent, raw.chapterUid, raw.chapterOffset, raw.updated, raw.source);
        }
        return new Pos(p, raw.chapterUid, raw.chapterOffset, raw.updated, raw.source);
    }

    /** 云端时间戳可能是毫秒，统一折成秒（对齐 MiuRead normalize_timestamp）。 */
    static long normalizeTimestamp(long ts) {
        if (ts > 100000000000L) return ts / 1000L;
        return ts;
    }

    /**
     * 两个云端源取更新时间新的；两源观测不一致只记为 disagreement（诊断），
     * 一律静默取新，不弹窗——对照 MiuRead beta.6「conflicting observations are not a user conflict」。
     * 时间戳相等时优先 Web（cookie 视图是上传回读的依据）。
     */
    static RemoteChoice chooseRemote(Pos web, Pos gateway) {
        if (web == null) return new RemoteChoice(gateway, false);
        if (gateway == null) return new RemoteChoice(web, false);
        long wt = normalizeTimestamp(web.updated);
        long gt = normalizeTimestamp(gateway.updated);
        boolean coordDisagree;
        if (uidKnown(web.chapterUid) && uidKnown(gateway.chapterUid)) {
            // 坐标是权威：跨章或同章偏移差 >12 字才算两源分歧
            coordDisagree = !web.chapterUid.equals(gateway.chapterUid)
                    || Math.abs(web.chapterOffset - gateway.chapterOffset) > OFFSET_TOLERANCE;
        } else {
            coordDisagree = Math.abs(web.percent - gateway.percent) > SAME_THRESHOLD;
        }
        Pos selected = (gt > wt || wt == 0L) ? gateway : web;
        return new RemoteChoice(selected, coordDisagree);
    }

    /**
     * 本地 vs 云端。两端 chapterUid 都可信时以「章节 + 章内偏移」为权威坐标：
     * 同章偏移差在容差内视为同一位置（百分比可能因目录结构不同而不同，不能据此判冲突）；
     * 跨章才按百分比差距判断谁领先。只有一端有坐标或都没有时退回百分比。
     */
    static int compare(Pos local, Pos remote) {
        if (local == null || remote == null) return SAME;
        double delta = remote.percent - local.percent;
        boolean bothCoord = uidKnown(local.chapterUid) && uidKnown(remote.chapterUid);
        if (bothCoord) {
            if (local.chapterUid.equals(remote.chapterUid)) {
                if (Math.abs(local.chapterOffset - remote.chapterOffset) <= OFFSET_TOLERANCE) {
                    return SAME;
                }
                // 同章但偏移差很大：按偏移方向决定谁更靠后
                return remote.chapterOffset > local.chapterOffset ? REMOTE_AHEAD : LOCAL_AHEAD;
            }
            // 跨章：章节顺序以百分比为准（我们无法从 uid 字符串推出先后）
            if (Math.abs(delta) <= SAME_THRESHOLD) return DIFFERENT_CHAPTER;
            return delta > 0 ? REMOTE_AHEAD : LOCAL_AHEAD;
        }
        if (Math.abs(delta) <= SAME_THRESHOLD) return SAME;
        return delta > 0 ? REMOTE_AHEAD : LOCAL_AHEAD;
    }

    /**
     * 综合决策。sourceDisagreement 来自 chooseRemote.conflict——只记日志，
     * 不触发弹窗（两源分歧已经静默取新，不是需要用户裁决的真冲突）。
     * 真正弹窗的只有 compare==DIFFERENT_CHAPTER：本地与云端跨章且差异可疑。
     */
    static Action decide(int compare, boolean sourceDisagreement, int trigger,
                         boolean pullOnOpen, boolean pushOnClose) {
        if (trigger == TRIGGER_OPEN && !pullOnOpen) return new Action(ACT_NONE, "开书拉取未开启");
        if (trigger == TRIGGER_CLOSE && !pushOnClose) return new Action(ACT_NONE, "关书上传未开启");
        if (compare == SAME) return new Action(ACT_NONE, "两端位置一致");
        if (compare == DIFFERENT_CHAPTER) return new Action(ACT_ASK, "跨章位置差异需确认");
        if (compare == REMOTE_AHEAD) return new Action(ACT_JUMP_REMOTE, "云端读得更远");
        if (trigger == TRIGGER_CLOSE || trigger == TRIGGER_MANUAL) {
            return new Action(ACT_UPLOAD_LOCAL, "本地读得更远，回传云端");
        }
        return new Action(ACT_KEEP_LOCAL, "保留本地进度");
    }

    static boolean uidKnown(String uid) {
        return uid != null && uid.length() > 0;
    }

    static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private Sync() {
    }
}
