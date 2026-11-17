#!/usr/bin/env python3
"""带 Range 支持的静态文件服务。

Python 自带的 `python -m http.server` **不支持 Range 请求**，对 ExoPlayer 这种
必须靠 Range 做 seek / 嗅探的播放器是致命的：客户端发 `Range: bytes=N-`，
服务端老老实实回 200 + 整个文件，ExoPlayer 发现响应和请求对不上，直接报
`ExoPlaybackException: Source error` —— 看起来像"解码失败"，其实是服务端不会分片。

用法：
    python tools/serve-range.py [端口] [目录]
    # 默认 8765 / 当前目录

模拟器访问宿主机用 `10.0.2.2`：
    adb shell am start -n work.luxiaoxiao.myembyhub/.ui.PlayerActivity \
        --es url 'http://10.0.2.2:8765/t.mp4' --es title LocalTest \
        --es item_id local --es media_source_id local --el start_ms 0
"""

import os
import re
import sys
from http.server import HTTPServer, SimpleHTTPRequestHandler
from socketserver import ThreadingMixIn

RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)")


class RangeHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        # ExoPlayer 会先发一个 Range: bytes=0- 探长度，这里明示支持
        self.send_header("Accept-Ranges", "bytes")
        super().end_headers()

    def do_GET(self):
        path = self.translate_path(self.path)
        rng = self.headers.get("Range")
        if not rng or not os.path.isfile(path):
            return super().do_GET()

        size = os.path.getsize(path)
        m = RANGE_RE.match(rng.strip())
        if not m:
            return super().do_GET()
        start_s, end_s = m.group(1), m.group(2)
        if start_s == "":                      # bytes=-N：最后 N 字节
            length = int(end_s or 0)
            start = max(0, size - length)
            end = size - 1
        else:
            start = int(start_s)
            end = int(end_s) if end_s else size - 1
            end = min(end, size - 1)
        if start > end or start >= size:
            self.send_error(416, "Requested Range Not Satisfiable")
            return

        length = end - start + 1
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header("Content-Length", str(length))
        self.send_header("Last-Modified", self.date_time_string(os.path.getmtime(path)))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


class ThreadedServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    root = sys.argv[2] if len(sys.argv) > 2 else "."
    os.chdir(root)
    print("serving %s on http://0.0.0.0:%d (Range enabled)" % (os.getcwd(), port))
    ThreadedServer(("0.0.0.0", port), RangeHandler).serve_forever()