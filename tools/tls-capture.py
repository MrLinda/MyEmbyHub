#!/usr/bin/env python3
"""
TCP 转发 + ClientHello 抓包器（只做诊断用，不做中间人解密）。

用法:
    python tools/tls-capture.py --listen 0.0.0.0:8443 --target 203.0.113.10:443

作用:
    把电视(或任何客户端)发往本机 8443 端口的 TLS 连接原样转发到真实服务器，
    同时把 客户端->服务器 方向的前 N 字节打印出来，
    这样就能看到 Android 4.3 真实发出的 ClientHello: SNI / cipher list / extensions。

因为转发是纯 TCP 透传，服务端证书原样下发，客户端仍能完成真实握手，
所以也能复现服务端返回的 alert。
"""
import argparse
import socket
import struct
import sys
import threading
import time

BUF = 8192
CAP = 4096

# 实时落盘，避免 stdout 缓冲导致看不到输出
OUT = None


def emit(s):
    print(s, flush=True)
    if OUT:
        try:
            with open(OUT, "a", encoding="utf-8") as f:
                f.write(s + "\n")
        except Exception:
            pass


def dump_bytes(title, data):
    emit(f"\n===== {title} ({len(data)} bytes) =====")
    for off in range(0, len(data), 16):
        chunk = data[off:off + 16]
        hexpart = " ".join(f"{b:02x}" for b in chunk)
        asciipart = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        emit(f"{off:04x}  {hexpart:<47}  {asciipart}")


def parse_client_hello(data):
    """尽力解析 TLS ClientHello: 版本 / cipher suites / extensions / SNI。"""
    try:
        if len(data) < 5 or data[0] != 0x16:
            return "  (不是 TLS handshake record)"
        p = 5
        if len(data) < p + 4:
            return "  (数据不足)"
        hs_type = data[p]
        if hs_type != 1:
            return f"  (handshake type={hs_type}, 不是 ClientHello)"
        p += 1
        # Handshake 消息长度是 3 字节
        hs_len = (data[p] << 16) | (data[p + 1] << 8) | data[p + 2]
        p += 3
        end = p + hs_len
        client_version = struct.unpack(">H", data[p:p + 2])[0]
        p += 2
        client_random = data[p:p + 32]
        p += 32
        sid_len = data[p]
        p += 1 + sid_len
        cs_len = struct.unpack(">H", data[p:p + 2])[0]
        p += 2
        cipher_list = []
        for i in range(0, cs_len, 2):
            c = struct.unpack(">H", data[p + i:p + i + 2])[0]
            cipher_list.append(f"0x{c:04x}")
        p += cs_len
        comp_len = data[p]
        p += 1 + comp_len
        out = [
            f"  ClientHello version : 0x{client_version:04x} "
            f"(TLS1.0=0x0301, TLS1.2=0x0303)",
            f"  random              : {client_random[:8].hex()}...",
            f"  cipher suites ({len(cipher_list)}):",
        ]
        for i in range(0, len(cipher_list), 6):
            out.append("      " + ", ".join(cipher_list[i:i + 6]))
        if p >= end:
            out.append("  (没有 extensions)")
            return "\n".join(out)
        ext_total = struct.unpack(">H", data[p:p + 2])[0]
        p += 2
        out.append(f"  extensions total len: {ext_total}")
        while p + 4 <= end:
            etype, elen = struct.unpack(">HH", data[p:p + 4])
            p += 4
            body = data[p:p + elen]
            p += elen
            name = {
                0: "server_name(SNI)", 5: "status_request", 10: "supported_groups",
                11: "ec_point_formats", 13: "signature_algorithms",
                16: "application_layer_protocol_negotiation(ALPN)",
                18: "signed_certificate_timestamp", 21: "padding",
                22: "encrypt_then_mac", 23: "extended_master_secret",
                35: "session_ticket", 43: "supported_versions", 51: "key_share",
                65281: "renegotiation_info(SCSV)",
            }.get(etype, f"unknown({etype})")
            extra = ""
            if etype == 0 and len(body) >= 5:
                ntype = body[2]
                nlen = struct.unpack(">H", body[3:5])[0]
                extra = f" -> hostname={body[5:5 + nlen].decode('ascii', 'replace')!r}"
            if etype == 10:
                groups = []
                for i in range(0, len(body) - 1, 2):
                    groups.append(f"0x{struct.unpack('>H', body[i:i + 2])[0]:04x}")
                extra = f" -> groups={groups}"
            if etype == 13:
                algs = []
                for i in range(0, len(body) - 1, 2):
                    algs.append(f"0x{struct.unpack('>H', body[i:i + 2])[0]:04x}")
                extra = f" -> sig_algs={algs}"
            if etype == 43:
                vers = []
                for i in range(0, len(body) - 1, 2):
                    vers.append(f"0x{struct.unpack('>H', body[i:i + 2])[0]:04x}")
                extra = f" -> versions={vers}"
            out.append(f"      ext {etype:>5} {name:<50} len={elen}{extra}")
        return "\n".join(out)
    except Exception as e:
        return f"  (解析失败: {e})"


def relay(src, dst, tag, capture, lock):
    try:
        while True:
            data = src.recv(BUF)
            if not data:
                break
            if tag == "C->S":
                with lock:
                    if len(capture["c2s"]) < CAP:
                        capture["c2s"].extend(data[:CAP - len(capture["c2s"])])
            else:
                with lock:
                    if len(capture["s2c"]) < CAP:
                        capture["s2c"].extend(data[:CAP - len(capture["s2c"])])
            try:
                dst.sendall(data)
            except Exception:
                break
    except Exception:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass


def parse_alert(data):
    """服务端第一包如果是 TLS alert，解析出来。"""
    try:
        if len(data) < 7 or data[0] != 0x15:
            return "  (不是 alert record)"
        level = data[5]
        desc = data[6]
        names = {0: "close_notify", 10: "unexpected_message", 20: "bad_record_mac",
                 40: "handshake_failure", 42: "bad_certificate", 43: "unsupported_certificate",
                 44: "certificate_revoked", 45: "certificate_expired",
                 46: "certificate_unknown", 47: "illegal_parameter",
                 48: "unknown_ca", 49: "access_denied", 50: "decode_error",
                 51: "decrypt_error", 70: "protocol_version", 71: "insufficient_security",
                 80: "internal_error", 90: "user_canceled", 109: "missing_extension",
                 112: "unrecognized_name", 120: "no_application_protocol"}
        return f"  TLS ALERT: level={level} desc={desc} ({names.get(desc, 'unknown')})"
    except Exception as e:
        return f"  (解析失败: {e})"


def handle(client, target_host, target_port, lock):
    emit(f"\n[+] 新连接 {client.getpeername()} -> {target_host}:{target_port}")
    try:
        upstream = socket.create_connection((target_host, target_port), timeout=15)
    except Exception as e:
        emit(f"[!] 连上游失败: {e}")
        client.close()
        return
    capture = {"c2s": [], "s2c": []}
    t1 = threading.Thread(target=relay, args=(client, upstream, "C->S", capture, lock), daemon=True)
    t2 = threading.Thread(target=relay, args=(upstream, client, "S->C", capture, lock), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    with lock:
        blob = bytes(capture["c2s"])
        sblob = bytes(capture["s2c"])
    if blob:
        emit("\n########## 捕获到的 ClientHello ##########")
        dump_bytes("Client->Server 首包", blob)
        emit(parse_client_hello(blob))
    else:
        emit("[!] 没捕获到客户端首包")
    if sblob:
        dump_bytes("Server->Client 首包", sblob)
        emit(parse_alert(sblob))
    try:
        client.close()
        upstream.close()
    except Exception:
        pass
    emit("[-] 连接结束\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="0.0.0.0:8443")
    ap.add_argument("--target", default="203.0.113.10:443")
    args = ap.parse_args()

    lhost, lport = args.listen.rsplit(":", 1)
    thost, tport = args.target.rsplit(":", 1)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((lhost, int(lport)))
    srv.listen(8)
    emit(f"[*] 监听 {lhost}:{lport} -> 转发至 {thost}:{tport}")
    emit("[*] 等待电视连接…")

    lock = threading.Lock()
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c, thost, int(tport), lock), daemon=True).start()


if __name__ == "__main__":
    main()

