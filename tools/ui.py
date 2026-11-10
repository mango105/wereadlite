#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""adb 驱动真机冒烟：截图、打印控件树、按文案点击。
用法：
  python tools/ui.py shot 名称
  python tools/ui.py tree
  python tools/ui.py tap 文案片段
  python tools/ui.py xy 400 640
  python tools/ui.py long 文案片段
  python tools/ui.py key 4|24|25|3|66|67
  python tools/ui.py swipe x1 y1 x2 y2
"""
import os
import re
import subprocess
import sys

ADB = os.environ.get("ADB", r"D:\EXE\Android\Sdk\platform-tools\adb.exe")
SERIAL = os.environ.get("SERIAL", "127.0.0.1:21503")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "shots")


def sh(args, binary=False):
    out = subprocess.run([ADB, "-s", SERIAL] + args, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def dump():
    sh(["shell", "uiautomator", "dump", "/sdcard/wl.xml"])
    xml = sh(["shell", "cat", "/sdcard/wl.xml"], binary=True).decode("utf-8", "replace")
    return re.findall(r"<node\b[^>]*/?>", xml)


def attrs(node):
    get = lambda k: (re.search(k + r'="([^"]*)"', node) or [None, ""])[1]
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    box = tuple(int(x) for x in b.groups()) if b else (0, 0, 0, 0)
    return {"text": get("text"), "desc": get("content-desc"), "cls": get("class"),
            "click": get("clickable"), "long": get("long-clickable"), "box": box}


def center(a):
    return (a["box"][0] + a["box"][2]) // 2, (a["box"][1] + a["box"][3]) // 2


def find(needle):
    for n in dump():
        a = attrs(n)
        if needle in a["text"] or needle in a["desc"]:
            return a
    return None


def tree():
    for n in dump():
        a = attrs(n)
        if a["text"] or a["desc"] or a["click"] == "true" or a["long"] == "true":
            print("%-34s text=%-18s desc=%-12s click=%s long=%s %s" % (
                a["cls"].split(".")[-1], a["text"], a["desc"], a["click"], a["long"], a["box"]))


def shot(name):
    if not os.path.isdir(SHOTS):
        os.makedirs(SHOTS)
    path = os.path.join(SHOTS, name + ".png")
    open(path, "wb").write(sh(["exec-out", "screencap", "-p"], binary=True))
    print("%s  (%d bytes)" % (path, os.path.getsize(path)))


def fatal(since_lines=400):
    log = sh(["shell", "logcat", "-d", "-t", str(since_lines)])
    hits = [l for l in log.splitlines()
            if re.search(r"E AndroidRuntime|FATAL|NoSuchMethod|ClassNotFound|IndexOutOfBounds|"
                         r"NullPointer|wereadlite.*Exception", l)]
    print("\n".join(hits[-25:]) if hits else "logcat: 无崩溃记录")
    return len(hits)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "shot":
        shot(sys.argv[2])
    elif cmd == "tree":
        tree()
    elif cmd == "tap":
        a = find(sys.argv[2])
        if not a:
            print("找不到控件：" + sys.argv[2])
            return
        x, y = center(a)
        print(sh(["shell", "input", "tap", str(x), str(y)]))
    elif cmd == "long":
        a = find(sys.argv[2])
        if not a:
            print("找不到控件：" + sys.argv[2])
            return
        x, y = center(a)
        print(sh(["shell", "input", "swipe", str(x), str(y), str(x), str(y), "700"]))
    elif cmd == "xy":
        print(sh(["shell", "input", "tap", sys.argv[2], sys.argv[3]]))
    elif cmd == "key":
        print(sh(["shell", "input", "keyevent", sys.argv[2]]))
    elif cmd == "swipe":
        print(sh(["shell", "input", "swipe"] + sys.argv[2:6] + ["400"]))
    elif cmd == "fatal":
        fatal()
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
