#!/usr/bin/env python3
"""Оставляет в geoip.dat / geosite.dat только нужные категории.

Файлы — это protobuf GeoIPList / GeoSiteList: повторяющееся поле 1, каждая запись —
сообщение, первое поле которого (1) — код категории. Записи копируются как есть.

Использование: trim_geo.py <вход.dat> <выход.dat> КАТЕГОРИЯ [КАТЕГОРИЯ ...]
"""
import sys


def varint(b, i):
    v = s = 0
    while True:
        c = b[i]
        i += 1
        v |= (c & 0x7F) << s
        s += 7
        if c < 0x80:
            return v, i


def main():
    src, dst, *wanted = sys.argv[1:]
    wanted = {w.upper() for w in wanted}
    b = open(src, "rb").read()
    out = bytearray()
    found = set()
    i = 0
    while i < len(b):
        start = i
        _tag, i = varint(b, i)
        length, i = varint(b, i)
        entry = b[i:i + length]
        i += length
        name_len, j = varint(entry, 1)
        name = entry[j:j + name_len].decode(errors="replace").upper()
        if name in wanted:
            out += b[start:i]
            found.add(name)
    missing = wanted - found
    if missing:
        sys.exit(f"нет категорий: {', '.join(sorted(missing))}")
    open(dst, "wb").write(out)
    print(f"{dst}: {len(out)} байт, категории: {', '.join(sorted(found))}")


if __name__ == "__main__":
    main()
