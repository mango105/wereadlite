package com.modu.wereadlite;

import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 内存版 SharedPreferences，仅给 SelfTest 在 JVM 里跑 Store 的统计/合并逻辑用。
 * 不碰 Android 运行时，commit() 直接写内存 Map。
 */
final class FakePrefs implements SharedPreferences {

    private final Map<String, Object> map = new LinkedHashMap<String, Object>();

    @Override
    public Map<String, ?> getAll() {
        return new LinkedHashMap<String, Object>(map);
    }

    @Override
    public String getString(String key, String def) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : def;
    }

    @Override
    public int getInt(String key, int def) {
        Object v = map.get(key);
        return v instanceof Integer ? (Integer) v : def;
    }

    @Override
    public long getLong(String key, long def) {
        Object v = map.get(key);
        return v instanceof Long ? (Long) v : def;
    }

    @Override
    public float getFloat(String key, float def) {
        Object v = map.get(key);
        return v instanceof Float ? (Float) v : def;
    }

    @Override
    public boolean getBoolean(String key, boolean def) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> def) {
        Object v = map.get(key);
        return v instanceof Set ? (Set<String>) v : def;
    }

    @Override
    public boolean contains(String key) {
        return map.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new EditImpl();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
    }

    // 测试辅助：直接塞值，方便断言
    void put(String key, Object value) {
        map.put(key, value);
    }

    private final class EditImpl implements Editor {
        private final Map<String, Object> pending = new LinkedHashMap<String, Object>();
        private final java.util.List<String> removals = new java.util.ArrayList<String>();
        private boolean clear;

        @Override
        public Editor putString(String key, String value) { pending.put(key, value); return this; }
        @Override
        public Editor putInt(String key, int value) { pending.put(key, value); return this; }
        @Override
        public Editor putLong(String key, long value) { pending.put(key, value); return this; }
        @Override
        public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
        @Override
        public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
        @Override
        public Editor putStringSet(String key, Set<String> value) { pending.put(key, value); return this; }
        @Override
        public Editor remove(String key) { removals.add(key); return this; }
        @Override
        public Editor clear() { clear = true; return this; }
        @Override
        public boolean commit() {
            if (clear) map.clear();
            for (String k : removals) map.remove(k);
            map.putAll(pending);
            return true;
        }
        @Override
        public void apply() { commit(); }
    }
}
