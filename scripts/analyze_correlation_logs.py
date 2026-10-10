#!/usr/bin/env python3
"""処理 ID(相関 ID)を 1 つ受け取り、全サービスのログからその処理の経過をまとめる(#1729)。

    docker compose logs --no-color | python3 scripts/analyze_correlation_logs.py <処理ID>
    python3 scripts/analyze_correlation_logs.py <処理ID> logs.txt

入力は `docker compose logs` の出力(`ai-1  | ...` の接頭辞付き)でも、`docker logs` の出力(接頭辞なし)
でもよく、1 行 1 JSON(Spring Boot の構造化ログ、logstash 形式)と旧来の平文が混在していてもよい。
処理 ID は JSON の `correlationId` 項目、平文の `[<id>]`、本文の `correlation_id=<id>` のいずれでも拾う。

出力する 4 つ:
  1. 時系列の経過
  2. サービス・区間ごとの所要時間(完了行の `duration_ms=`)
  3. 同じ呼び出し先・操作の回数(2 回以上は重複の疑い)
  4. WARN / ERROR の行

標準ライブラリだけで動く。
"""

import argparse
import json
import re
import sys
from datetime import datetime, timedelta, timezone

LEVELS = ("TRACE", "DEBUG", "INFO", "WARN", "ERROR")

_PREFIX = re.compile(r"^(\S+)\s+\|\s?(.*)$")
_TS = re.compile(
    r"^(\d{4}-\d\d-\d\d)[T ](\d\d:\d\d:\d\d)(?:[.,](\d+))?(Z|[+-]\d\d:?\d\d)?")
_LEVEL = re.compile(r"\b(TRACE|DEBUG|INFO|WARN|ERROR)\b")
_BRACKET_CID = re.compile(r"\[([^\]\s]*)\]\s+-\s+(.*)$")
_COLON_MSG = re.compile(r"\s:\s(.*)$")
_CID_IN_MESSAGE = re.compile(r"correlation_id=([A-Za-z0-9-]+)")
_DURATION = re.compile(r"\bduration_ms=(\d+)")
_SYNC = re.compile(r"^sync call: target=(\S+) .*?operation=(.*?) duration_ms=")
_REQUEST = re.compile(r"^(service|gateway) request: method=(\S+) path=(\S+)")


def parse_timestamp(text):
    """ISO-8601 の時刻を UTC の datetime にする。読めなければ None。"""
    if not text:
        return None
    m = _TS.match(str(text))
    if not m:
        return None
    day, clock, frac, zone = m.groups()
    micro = int((frac or "0")[:6].ljust(6, "0"))
    if zone in (None, "Z"):
        offset = timedelta(0)
    else:
        sign = -1 if zone[0] == "-" else 1
        digits = zone[1:].replace(":", "")
        offset = sign * timedelta(hours=int(digits[:2]), minutes=int(digits[2:]))
    try:
        y, mo, d = (int(x) for x in day.split("-"))
        h, mi, s = (int(x) for x in clock.split(":"))
        local = datetime(y, mo, d, h, mi, s, micro, tzinfo=timezone(offset))
    except ValueError:
        return None
    return local.astimezone(timezone.utc)


def canonical_service(name):
    """compose の接頭辞(`content-1`)と JSON の service 項目(`lets-blog-content`)を同じ名前にする。"""
    if not name:
        return "?"
    name = re.sub(r"-\d+$", "", name)
    return name[len("lets-blog-"):] if name.startswith("lets-blog-") else name


def _record(service, timestamp, level, logger, message, cid):
    return {"service": canonical_service(service), "timestamp": timestamp, "level": level,
            "logger": logger, "message": message, "correlation_id": cid}


def _cid_from_message(message):
    m = _CID_IN_MESSAGE.search(message or "")
    return m.group(1) if m else None


def parse_line(line):
    """1 行を {service, timestamp, level, logger, message, correlation_id} にする。空行は None。

    JSON でも平文でもない行は、メッセージだけを持つ記録として返す(処理 ID は本文から探す)。
    """
    line = line.rstrip("\r\n")
    if not line.strip():
        return None
    service = None
    body = line
    m = _PREFIX.match(line)
    if m:
        service, body = m.group(1), m.group(2)
    body = body.strip()

    if body.startswith("{"):
        try:
            obj = json.loads(body)
        except ValueError:
            obj = None
        if isinstance(obj, dict):
            message = str(obj.get("message", ""))
            cid = obj.get("correlationId") or obj.get("correlation_id") or _cid_from_message(message)
            return _record(
                obj.get("service") or service or "?",
                obj.get("@timestamp") or obj.get("timestamp"),
                str(obj.get("level", "")).upper() or None,
                obj.get("logger_name") or obj.get("logger"),
                message, cid or None)

    ts = _TS.match(body)
    timestamp = ts.group(0) if ts else None
    rest = body[ts.end():] if ts else body
    lv = _LEVEL.search(rest)
    level = lv.group(1) if lv else None
    bracket = _BRACKET_CID.search(rest)
    if bracket:
        cid, message = (bracket.group(1) or None), bracket.group(2)
    else:
        colon = _COLON_MSG.search(rest)
        cid, message = None, (colon.group(1) if colon else rest.strip())
    cid = cid or _cid_from_message(message)
    return _record(service or "?", timestamp, level, None, message, cid)


def collect(lines, correlation_id):
    """処理 ID の行だけを、時刻順(同時刻は入力順)に集める。ID は完全一致で見る。"""
    found = []
    for index, line in enumerate(lines):
        rec = parse_line(line)
        if rec is None or rec["correlation_id"] != correlation_id:
            continue
        rec["_when"] = parse_timestamp(rec["timestamp"])
        found.append((index, rec))
    floor = datetime.min.replace(tzinfo=timezone.utc)
    found.sort(key=lambda pair: (pair[1]["_when"] or floor, pair[0]))
    return [rec for _, rec in found]


def _segment_name(message):
    """完了行から区間の名前を作る。所要時間を持たない行は None。"""
    if not _DURATION.search(message):
        return None
    sync = _SYNC.match(message)
    if sync:
        return "sync call %s %s" % (sync.group(1), sync.group(2))
    req = _REQUEST.match(message)
    if req:
        return "%s %s" % (req.group(2), req.group(3))
    return re.split(r"\s+\w+=", message, maxsplit=1)[0].strip()[:80]


def segments(records):
    """(サービス, 区間) ごとに完了行の duration_ms を集める。入力順に初出の順で返す。"""
    grouped = {}
    for rec in records:
        name = _segment_name(rec["message"])
        if name is None:
            continue
        key = (rec["service"], name)
        grouped.setdefault(key, []).append(int(_DURATION.search(rec["message"]).group(1)))
    return [{"service": s, "segment": n, "durations_ms": d} for (s, n), d in grouped.items()]


def duplicates(records):
    """同じサービスが同じ区間(呼び出し先・操作)を 2 回以上記録していれば、その回数を返す。"""
    return [
        {"service": s["service"], "call": s["segment"], "count": len(s["durations_ms"]),
         "durations_ms": s["durations_ms"]}
        for s in segments(records) if len(s["durations_ms"]) > 1
    ]


def problems(records):
    return [r for r in records if r["level"] in ("WARN", "ERROR")]


def render(records, correlation_id):
    out = ["処理 ID: %s" % correlation_id]
    if not records:
        out.append("この処理 ID の行は見つかりません。ID の綴り・ログの範囲(--since など)を確認してください。")
        return "\n".join(out) + "\n"
    out.append("該当行: %d 件" % len(records))

    out += ["", "== 1. 時系列"]
    for r in records:
        out.append("%s  %-22s %-5s %s" % (r["timestamp"] or "-", r["service"], r["level"] or "-", r["message"]))

    out += ["", "== 2. 区間ごとの所要時間(長い順)"]
    segs = segments(records)
    if not segs:
        out.append("(duration_ms を持つ完了行がありません)")
    for s in sorted(segs, key=lambda s: -max(s["durations_ms"])):
        d = s["durations_ms"]
        out.append("%-22s %-60s max=%dms total=%dms count=%d %s" % (s["service"], s["segment"], max(d), sum(d), len(d), d))
    spans = {}
    for r in records:
        if r["_when"]:
            lo, hi = spans.get(r["service"], (r["_when"], r["_when"]))
            spans[r["service"]] = (min(lo, r["_when"]), max(hi, r["_when"]))
    for service, (lo, hi) in spans.items():
        out.append("%-22s ログ上の最初から最後まで %dms" % (service, round((hi - lo).total_seconds() * 1000)))

    out += ["", "== 3. 重複した呼び出し(同じ呼び出し先・操作が 2 回以上)"]
    dups = duplicates(records)
    if not dups:
        out.append("(なし)")
    for d in dups:
        out.append("x%d  %s  %s  %s" % (d["count"], d["service"], d["call"], d["durations_ms"]))

    out += ["", "== 4. WARN / ERROR"]
    bad = problems(records)
    if not bad:
        out.append("(なし)")
    for r in bad:
        out.append("%s  %-22s %-5s %s" % (r["timestamp"] or "-", r["service"], r["level"], r["message"]))
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description="処理 ID のログを集めて経過・所要時間・重複・失敗を出す")
    ap.add_argument("correlation_id", help="処理 ID(相関 ID)")
    ap.add_argument("file", nargs="?", default="-", help="docker compose logs の出力。省略か - で標準入力")
    args = ap.parse_args(argv)
    try:
        if args.file == "-":
            lines = sys.stdin.read().splitlines()
        else:
            with open(args.file, encoding="utf-8", errors="replace") as f:
                lines = f.read().splitlines()
    except OSError as e:
        print("入力を読めません: %s" % e, file=sys.stderr)
        return 2
    sys.stdout.write(render(collect(lines, args.correlation_id), args.correlation_id))
    return 0


if __name__ == "__main__":
    sys.exit(main())
