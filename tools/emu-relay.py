#!/usr/bin/env python3
"""
把「宿主 LAN → 模拟器里的 5103」接通。

## 为什么需要这个

模拟器（QEMU）用的是 **user-mode NAT**：guest 自己看到的 IP 是 `10.0.2.15`，
这个地址**只存在于模拟器进程内部**，宿主机根本没有到它的路由。
（实测：宿主机直接请求 `http://10.0.2.15:5103/` → 连接超时，而
 `adb forward tcp:15103 tcp:5103` 之后 `http://127.0.0.1:15103/` → HTTP 200。）

也就是说：

    adb forward  → 只能到 127.0.0.1，电脑上的浏览器能用，**手机用不了**
    adb emu redir → 同样只监听 127.0.0.1

于是手机扫出来的二维码（里面是 `http://10.0.2.15:5103/...`）在手机上永远打不开。
这个脚本补最后一段：在宿主机的 LAN 地址上开一个 TCP 转发，把流量原样送到
`adb forward` 建好的本地端口。

    手机 ──LAN──> 宿主机 0.0.0.0:8088 ──> 127.0.0.1:15103 ──adb forward──> 模拟器 5103

## 用法

    # 1) 先建 adb 转发（15103 是本机的入口，5103 是模拟器里那个服务）
    adb forward tcp:15103 tcp:5103

    # 2) 起本脚本（默认 0.0.0.0:8088 → 127.0.0.1:15103）
    python tools/emu-relay.py

    # 3) 手机浏览器打开 http://<本机局域网IP>:8088/?mode=login

⚠️ 一个连接对一条上游连接，纯字节搬运，不解析 HTTP —— 手机页面里的
   `fetch('/api/login')` 走同一 origin，所以不需要改页面。

⚠️ 这只是**开发期**的桥。真机（乐视电视）自己就在局域网上，
   二维码里的地址直接可用，不需要这个脚本。
"""

import argparse
import socket
import sys
import threading

# 日志重定向到文件时，Windows 控制台默认编码（GBK）会把中文写成乱码。
# 显式钉成 UTF-8，`Get-Content -Encoding utf8` 就能读。
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except (AttributeError, OSError):
    pass


def pump(src: socket.socket, dst: socket.socket) -> None:
    """单向搬运，直到任一端关闭。"""
    try:
        while True:
            # 4KB 对这种小页面足够；别贪大会让 button 响应变钝
            chunk = src.recv(4096)
            if not chunk:
                break
            dst.sendall(chunk)
    except (OSError, ValueError):
        pass
    finally:
        # 半关闭而不是硬关：对面可能还有话没说完（HTTP 响应体）
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client: socket.socket, upstream_host: str, upstream_port: int) -> None:
    try:
        upstream = socket.create_connection((upstream_host, upstream_port), timeout=10)
    except OSError as e:
        print(f"[relay] 连不上上游 {upstream_host}:{upstream_port} —— {e}", file=sys.stderr)
        try:
            client.close()
        except OSError:
            pass
        return

    t1 = threading.Thread(target=pump, args=(client, upstream), daemon=True)
    t2 = threading.Thread(target=pump, args=(upstream, client), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    try:
        client.close()
    except OSError:
        pass
    try:
        upstream.close()
    except OSError:
        pass


def main() -> int:
    ap = argparse.ArgumentParser(description="宿主 LAN → adb forward 端口的 TCP 桥")
    ap.add_argument("--listen-port", type=int, default=8088, help="宿主机监听端口（默认 8088）")
    ap.add_argument("--upstream-port", type=int, default=15103, help="adb forward 的本地端口（默认 15103）")
    ap.add_argument("--upstream-host", default="127.0.0.1", help="上游地址（默认 127.0.0.1）")
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind(("0.0.0.0", args.listen_port))
    except OSError as e:
        print(
            f"[relay] 绑定 0.0.0.0:{args.listen_port} 失败：{e}\n"
            f"        端口被占用就换一个：--listen-port 8090",
            file=sys.stderr,
        )
        return 1
    srv.listen(16)

    print(
        f"[relay] 0.0.0.0:{args.listen_port} -> {args.upstream_host}:{args.upstream_port} 就绪\n"
        f"[relay] 手机请打开 http://<本机局域网IP>:{args.listen_port}/?mode=login\n"
        f"[relay] 别忘了先跑： adb forward tcp:{args.upstream_port} tcp:5103",
        flush=True,
    )

    try:
        while True:
            client, addr = srv.accept()
            print(f"[relay] 来自 {addr[0]}:{addr[1]}", flush=True)
            threading.Thread(
                target=handle,
                args=(client, args.upstream_host, args.upstream_port),
                daemon=True,
            ).start()
    except KeyboardInterrupt:
        print("\n[relay] 退出", flush=True)
    finally:
        try:
            srv.close()
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
