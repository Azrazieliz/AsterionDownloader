"""Minimal, dependency-free LOCAL Chrome DevTools Protocol WebSocket client.

JAVIDEO's selected-player probe needs CDP on the owner's Windows PC, whose
existing Python installations have neither websockets nor websocket-client.
This deliberately accepts only local ws:// CDP targets; it does not install
packages, connect to remote WebSockets or change browser account sessions.
"""
import base64
import hashlib
import os
import socket
import struct
import time
from urllib.parse import urlsplit

_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

class LocalCdpSocket:
    def __init__(self, url, open_timeout=7, close_timeout=2, max_size=1_000_000):
        parsed = urlsplit(url)
        if (parsed.scheme != "ws" or parsed.hostname not in
                ("127.0.0.1", "localhost", "::1") or not parsed.port or
                parsed.username or parsed.password or parsed.fragment):
            raise ValueError("CDP WebSocket must be a local non-authenticated ws:// URL")
        self._max_size = max_size
        self._close_timeout = close_timeout
        self._buf = bytearray()
        self._socket = socket.create_connection(
            (parsed.hostname, parsed.port), timeout=open_timeout)
        self._socket.settimeout(open_timeout)
        try:
            nonce = base64.b64encode(os.urandom(16)).decode("ascii")
            path = parsed.path or "/"
            if parsed.query:
                path += "?" + parsed.query
            handshake = (
                f"GET {path} HTTP/1.1\r\n"
                f"Host: {parsed.hostname}:{parsed.port}\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {nonce}\r\n"
                "Sec-WebSocket-Version: 13\r\n"
                "Origin: http://localhost\r\n\r\n"
            )
            self._socket.sendall(handshake.encode("ascii"))
            response = self._until(b"\r\n\r\n", 32768).decode("latin-1")
            lines = response.split("\r\n")
            if not lines[0].startswith("HTTP/1.1 101 "):
                raise ConnectionError("Local CDP WebSocket rejected the upgrade")
            headers = {}
            for line in lines[1:]:
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()
            expected = base64.b64encode(
                hashlib.sha1((nonce + _GUID).encode("ascii")).digest()
            ).decode("ascii")
            if (headers.get("sec-websocket-accept") != expected or
                    headers.get("upgrade", "").lower() != "websocket"):
                raise ConnectionError("Invalid local CDP WebSocket handshake")
        except BaseException:
            self._socket.close()
            raise

    def _until(self, delimiter, bound):
        while delimiter not in self._buf:
            if len(self._buf) > bound:
                raise ValueError("Oversized CDP handshake")
            chunk = self._socket.recv(4096)
            if not chunk:
                raise EOFError("Local CDP disconnected")
            self._buf.extend(chunk)
        end = self._buf.index(delimiter) + len(delimiter)
        data = bytes(self._buf[:end])
        del self._buf[:end]
        return data

    def _take(self, length, deadline):
        if length > self._max_size + 16:
            raise ValueError("Oversized CDP response")
        while len(self._buf) < length:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("CDP response timed out")
            self._socket.settimeout(remaining)
            chunk = self._socket.recv(min(65536, length - len(self._buf)))
            if not chunk:
                raise EOFError("Local CDP disconnected")
            self._buf.extend(chunk)
        result = bytes(self._buf[:length])
        del self._buf[:length]
        return result

    def _write_frame(self, opcode, content):
        if len(content) > self._max_size:
            raise ValueError("Oversized CDP command")
        if len(content) < 126:
            header = bytes([0x80 | opcode, 0x80 | len(content)])
        elif len(content) <= 65535:
            header = bytes([0x80 | opcode, 0x80 | 126]) + struct.pack("!H", len(content))
        else:
            header = bytes([0x80 | opcode, 0x80 | 127]) + struct.pack("!Q", len(content))
        mask = os.urandom(4)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(content))
        self._socket.sendall(header + mask + masked)

    def send(self, text):
        self._write_frame(0x1, text.encode("utf-8"))

    def recv(self, timeout=7):
        deadline = time.monotonic() + timeout
        fragments = bytearray()
        in_message = False
        while True:
            header = self._take(2, deadline)
            opcode = header[0] & 0x0F
            fin = bool(header[0] & 0x80)
            count = header[1] & 0x7F
            if count == 126:
                count = struct.unpack("!H", self._take(2, deadline))[0]
            elif count == 127:
                count = struct.unpack("!Q", self._take(8, deadline))[0]
            if count > self._max_size:
                raise ValueError("Oversized CDP response frame")
            mask = self._take(4, deadline) if header[1] & 0x80 else None
            payload = self._take(count, deadline)
            if mask:
                payload = bytes(v ^ mask[i % 4] for i, v in enumerate(payload))
            if opcode == 0x8:
                raise EOFError("Local CDP closed the WebSocket")
            if opcode == 0x9:
                self._write_frame(0xA, payload)
                continue
            if opcode == 0xA:
                continue
            if opcode not in (0x0, 0x1):
                continue
            if opcode == 0x1:
                fragments.clear()
                in_message = True
            if not in_message:
                raise ValueError("Unexpected CDP continuation")
            fragments.extend(payload)
            if len(fragments) > self._max_size:
                raise ValueError("Oversized CDP message")
            if fin:
                return fragments.decode("utf-8")

    def close(self):
        self._socket.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

def connect(url, open_timeout=7, close_timeout=2, max_size=1_000_000):
    return LocalCdpSocket(
        url, open_timeout=open_timeout, close_timeout=close_timeout,
        max_size=max_size)
