package com.modu.wereadlite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 极简 JSON 解析：够用即可，避免引入三方库。 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src == null ? "" : src;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.skip();
        if (j.pos >= j.src.length()) return null;
        return j.value();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    public static Map<String, Object> map(Object o, String... path) {
        Object cur = o;
        for (String key : path) {
            if (!(cur instanceof Map)) return new LinkedHashMap<String, Object>();
            cur = ((Map<String, Object>) cur).get(key);
        }
        return asMap(cur);
    }

    public static List<Object> list(Object o, String... path) {
        Object cur = o;
        for (String key : path) {
            if (!(cur instanceof Map)) return new ArrayList<Object>();
            cur = ((Map<String, Object>) cur).get(key);
        }
        return asList(cur);
    }

    /** 依次尝试多个 key，返回第一个非空字符串。 */
    public static String str(Object o, String... keys) {
        if (!(o instanceof Map)) return "";
        Map<String, Object> m = asMap(o);
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                String s = String.valueOf(v);
                if (s.length() > 0 && !"null".equals(s)) return s;
            }
        }
        return "";
    }

    /** 取长整型数值；时间戳这类超过 int 范围的字段必须用这个，避免 intValue 溢出。 */
    public static long longNum(Object o, String key) {
        if (!(o instanceof Map)) return 0L;
        Object v = asMap(o).get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0L;
        }
    }

    public static int num(Object o, String key) {
        if (!(o instanceof Map)) return 0;
        Object v = asMap(o).get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private Object value() {
        skip();
        if (pos >= src.length()) return null;
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                pos += 4;
                return Boolean.TRUE;
            case 'f':
                pos += 5;
                return Boolean.FALSE;
            case 'n':
                pos += 4;
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        pos++;
        while (true) {
            skip();
            if (pos >= src.length()) break;
            if (src.charAt(pos) == '}') {
                pos++;
                break;
            }
            if (src.charAt(pos) == ',') {
                pos++;
                continue;
            }
            if (src.charAt(pos) != '"') {
                pos++;
                continue;
            }
            String key = string();
            skip();
            if (pos < src.length() && src.charAt(pos) == ':') pos++;
            m.put(key, value());
        }
        return m;
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        pos++;
        while (true) {
            skip();
            if (pos >= src.length()) break;
            char c = src.charAt(pos);
            if (c == ']') {
                pos++;
                break;
            }
            if (c == ',') {
                pos++;
                continue;
            }
            int before = pos;
            l.add(value());
            if (pos == before) pos++;
        }
        return l;
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                if (pos >= src.length()) break;
                char e = src.charAt(pos++);
                switch (e) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        if (pos + 4 <= src.length()) {
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        break;
                    default:
                        sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object number() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        String t = src.substring(start, pos);
        if (t.length() == 0) {
            pos++;
            return null;
        }
        try {
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                return Long.valueOf(Long.parseLong(t));
            }
            return Double.valueOf(Double.parseDouble(t));
        } catch (Exception e) {
            return null;
        }
    }

    private void skip() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') pos++;
            else break;
        }
    }

    public static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 简单 map → JSON 对象（写划线/想法的请求体）。值支持 String/Number/Boolean。 */
    public static String write(java.util.Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append(v);
            else sb.append(quote(String.valueOf(v)));
        }
        return sb.append('}').toString();
    }
}
