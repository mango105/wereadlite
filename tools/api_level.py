#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""扫描 classes.dex 里引用到的 android/* 方法，对照 SDK 的 api-versions.xml 找出
「高于目标 API  level」的调用。编译时用的是 android-17 桩，抓不到 15~17 才加入的方法，
在 Android 4.0/4.1 电纸书上会直接 NoSuchMethodError，所以这一步必须单独查。

用法：python tools/api_level.py [dex 路径] [允许的最低目标 level，默认 14]
"""
import os
import re
import struct
import sys
import zipfile
from xml.etree import ElementTree

SDK = os.environ.get("ANDROID_HOME") or r"D:\EXE\Android\Sdk"


def find_api_xml():
    best = None
    base = os.path.join(SDK, "platforms")
    if not os.path.isdir(base):
        return None
    for d in sorted(os.listdir(base)):
        p = os.path.join(base, d, "data", "api-versions.xml")
        if os.path.isfile(p):
            best = p  # 取版本号最高的那份
    return best


def uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def dex_strings(path):
    data = open(path, "rb").read()
    size, off = struct.unpack_from("<II", data, 0x38)
    strs = []
    for i in range(size):
        idx = struct.unpack_from("<I", data, off + 4 * i)[0]
        _, pos = uleb128(data, idx)
        end = data.index(b"\0", pos)
        strs.append(data[pos:end].decode("utf-8", "replace"))
    return data, strs


def dex_methods(path):
    """返回 [(类描述符, 方法名, 描述符)]。"""
    data, strs = dex_strings(path)
    tsize, toff = struct.unpack_from("<II", data, 0x40)
    psize, poff = struct.unpack_from("<II", data, 0x48)
    msize, moss = struct.unpack_from("<II", data, 0x58)
    types = [strs[struct.unpack_from("<I", data, toff + 4 * i)[0]] for i in range(tsize)]
    protos = []
    for i in range(psize):
        _, ret, params_off = struct.unpack_from("<III", data, poff + 12 * i)
        args = ""
        if params_off:
            cnt = struct.unpack_from("<I", data, params_off)[0]
            for j in range(cnt):
                args += types[struct.unpack_from("<H", data, params_off + 4 + 2 * j)[0]]
        protos.append((types[ret], args))
    out = []
    for i in range(msize):
        cls, proto, name = struct.unpack_from("<HHI", data, moss + 8 * i)
        ret, args = protos[proto]
        out.append((types[cls], strs[name], "(" + args + ")" + ret))
    return out


def level_of(text):
    """api-versions.xml 里 since 可能是 '15'、'36.1' 甚至 'current'，取整数部分，认不出就当最新。"""
    m = re.match(r"^\s*(\d+)", text or "")
    return int(m.group(1)) if m else 9999


def load_levels(xml_path):
    """api-versions.xml：since 只写在高于 API 1 的条目上，方法名是完整签名 name(desc)。
    removed="N" 表示 N 版本起被删，引用它同样会 NoSuchMethodError。"""
    tree = ElementTree.parse(xml_path)
    methods = {}
    by_name = {}
    classes = {}
    for c in tree.getroot():
        name = c.get("name")
        lvl = level_of(c.get("since", "1"))
        classes[name] = lvl
        for m in c.findall("method"):
            sig = m.get("name")
            since = level_of(m.get("since")) if m.get("since") else lvl
            if m.get("removed"):
                since = 9999
            methods[(name, sig)] = since
            by_name.setdefault((name, sig.split("(")[0]), []).append(since)
    return methods, by_name, classes


def plain(cls):
    """Landroid/app/Activity; -> android/app/Activity"""
    return cls[1:-1] if cls.startswith("L") and cls.endswith(";") else cls


def lookup(methods, by_name, classes, cls, name, desc):
    key = (cls, name + desc)
    if key in methods:
        return methods[key], "方法"
    k = (cls, name)
    if k in by_name:
        # 签名对不上（擦除差异/重载），取最危险的那个级别
        return max(by_name[k]), "同名签名"
    if cls in classes:
        return classes[cls], "整类"
    return None, None


def main():
    dex = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "build", "dex", "classes.dex")
    target = int(sys.argv[2]) if len(sys.argv) > 2 else 14
    xml = find_api_xml()
    if not xml:
        print("找不到 api-versions.xml，跳过检查")
        return 0
    if dex.endswith(".apk"):
        tmp = os.path.join(os.path.dirname(dex), "classes.dex")
        with zipfile.ZipFile(dex) as z:
            open(tmp, "wb").write(z.read("classes.dex"))
        dex = tmp
    methods, by_name, classes = load_levels(xml)
    refs = dex_methods(dex)
    bad = []
    unknown = []
    scanned = 0
    for cls, name, desc in refs:
        c = plain(cls)
        if not (c.startswith("android/") or c.startswith("dalvik/")
                or c.startswith("org/apache/harmony") or c.startswith("org/json")
                or c.startswith("org/w3c") or c.startswith("org/xml")
                or c.startswith("javax/microedition")):
            continue
        scanned += 1
        lvl, how = lookup(methods, by_name, classes, c, name, desc)
        if lvl is None:
            unknown.append((c, name, desc))
        elif lvl > target:
            bad.append((lvl, c, name, desc, how))

    print("dex           :", dex)
    print("api-versions  :", xml)
    print("框架引用      :", scanned, "/ 总方法引用", len(refs))
    print("target level  : %d" % target)
    bad = sorted(set(bad))
    if bad:
        print("\n高于 API %d 的调用（低版本设备上会抛 NoSuchMethodError）:" % target)
        for lvl, c, name, desc, how in bad:
            print("  [%s] %s->%s%s   (%s)" % ("_removed" if lvl == 9999 else lvl, c, name, desc, how))
        return 1
    print("\nOK：没有高于 API %d 的框架调用" % target)
    if unknown:
        print("（%d 个引用在 api-versions.xml 里查不到，通常是隐藏/内部 API，需人工确认）" % len(set(unknown)))
        for k in sorted(set(unknown))[:12]:
            print("  ", k[0] + "->" + k[1] + k[2])
    return 0


if __name__ == "__main__":
    sys.exit(main())
