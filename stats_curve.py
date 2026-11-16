#!/usr/bin/env python3
"""拉 GitHub release 下载数并与历史快照对比，输出各版本速率。"""
import json, sys, os, urllib.request, datetime

TOKEN = open("/storage/emulated/0/你要的那个什么GitHub token.txt").read().strip()
REPO = "Xposed-Modules-Repo/io.github.sweetpotato_digua.mbpu"
PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".stats_history.json")


def req(url):
    r = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "mbpu-stats",
    })
    with urllib.request.urlopen(r, timeout=60) as resp:
        return json.loads(resp.read().decode())


rels = req(f"https://api.github.com/repos/{REPO}/releases?per_page=10")
now = datetime.datetime.now(datetime.timezone.utc)
snap = {"ts": now.isoformat(), "releases": {}}
for r in rels:
    snap["releases"][r["tag_name"]] = {
        "downloads": sum(a["download_count"] for a in r["assets"]),
        "published": r["published_at"],
        "name": r["name"],
    }

hist = json.load(open(PATH)) if os.path.exists(PATH) else []
prev = hist[-1] if hist else None
hist.append(snap)
json.dump(hist, open(PATH, "w"), indent=2, ensure_ascii=False)


def age_h(iso):
    t = datetime.datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return max((now - t).total_seconds() / 3600, 0.01)


print(f"{'版本':<12}{'累计':>6}{'增量':>6}{'已发布h':>9}{'次/小时':>10}")
print("-" * 45)
for tag, v in snap["releases"].items():
    d = v["downloads"]
    delta = d - prev["releases"].get(tag, {}).get("downloads", 0) if prev else 0
    h = age_h(v["published"])
    print(f"{tag:<12}{d:>6}{delta:>6}{h:>9.1f}{d / h:>10.2f}")
print(f"\n上次采样：{prev['ts'][:19] if prev else '（首次）'}  本次：{now.isoformat()[:19]}Z")
