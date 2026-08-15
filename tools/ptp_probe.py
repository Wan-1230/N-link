#!/usr/bin/env python3
"""Minimal PTP/IP probe for Nikon cameras (read-only).

Use it against the real camera WiFi IP to establish a ground truth: if this
probe succeeds and the app fails, the problem is in the app; if the probe
fails, the camera/WiFi/other-host state is the problem.

Usage:
    python tools/ptp_probe.py --host 192.168.1.1 --port 15740
    python tools/ptp_probe.py --host 192.168.1.1 --keepalive 5
"""

import argparse
import socket
import struct
import time


def recv_exact(conn, size):
    data = b""
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            raise EOFError("connection closed")
        data += chunk
    return data


def read_packet(conn):
    header = recv_exact(conn, 8)
    length, ptype = struct.unpack("<II", header)
    payload = recv_exact(conn, length - 8)
    return ptype, payload


def send_packet(conn, ptype, payload=b""):
    conn.sendall(struct.pack("<II", 8 + len(payload), ptype) + payload)


def ptp_str(data, offset):
    length = data[offset]
    raw = data[offset + 1:offset + 1 + length * 2]
    return raw.decode("utf-16-le"), offset + 1 + length * 2


def utf16_nt(data, offset):
    end = offset
    while end + 1 < len(data) and (data[end] != 0 or data[end + 1] != 0):
        end += 2
    return data[offset:end].decode("utf-16-le")


class Probe:
    def __init__(self, host, port, guid, name):
        self.host = host
        self.port = port
        self.guid = guid
        self.name = name
        self.cmd = None
        self.evt = None
        self.session = None
        self.tx = 0

    def connect(self):
        self.cmd = socket.create_connection((self.host, self.port), 10)
        self.cmd.settimeout(15)
        payload = self.guid + (self.name + "\x00").encode("utf-16-le") + struct.pack("<HH", 0, 1)
        send_packet(self.cmd, 0x01, payload)
        ptype, data = read_packet(self.cmd)
        if ptype != 0x02:
            raise RuntimeError("expected INIT_CMD_ACK, got 0x%04X" % ptype)
        self.session = struct.unpack_from("<I", data, 0)[0]
        camera_guid = data[4:20]
        camera_name = utf16_nt(data, 20)
        print("INIT_CMD_ACK session=0x%08X camera=%s guid=%s" %
              (self.session, camera_name, camera_guid.hex()))

        self.evt = socket.create_connection((self.host, self.port), 10)
        self.evt.settimeout(15)
        send_packet(self.evt, 0x03, struct.pack("<I", self.session))
        ptype, _ = read_packet(self.evt)
        if ptype != 0x04:
            raise RuntimeError("expected INIT_EVENT_ACK, got 0x%04X" % ptype)
        print("INIT_EVENT_ACK ok")

        self._request(0x1002, [self.session])
        self._request(0x90C8)

    def _next_tx(self):
        self.tx += 1
        return self.tx

    def _request(self, op, params=(), want_data=False):
        tx = self._next_tx()
        payload = struct.pack("<IHI", 1, op, tx) + struct.pack("<%dI" % len(params), *params)
        send_packet(self.cmd, 0x06, payload)
        data = b""
        while True:
            ptype, packet = read_packet(self.cmd)
            if ptype == 0x07:
                code = struct.unpack_from("<H", packet, 0)[0]
                resp_tx = struct.unpack_from("<I", packet, 2)[0]
                if resp_tx != tx:
                    continue
                print("  response op=0x%04X code=0x%04X data=%d bytes" % (op, code, len(data)))
                if want_data and code != 0x2001:
                    raise RuntimeError("op 0x%04X rejected with 0x%04X" % (op, code))
                return code, data
            if ptype in (0x09,):
                continue
            if ptype in (0x0A, 0x0C):
                data += packet[4:]
            else:
                print("  unexpected packet type 0x%04X" % ptype)

    def run(self):
        self.connect()
        print("\nGetDeviceInfo:")
        _, data = self._request(0x1001, want_data=True)
        offset = 0
        standard_version, vendor_id, vendor_version, functional_mode = struct.unpack_from("<HIHH", data, offset)
        offset = 12
        print("  standardVersion=%d vendorId=0x%08X mode=%d" %
              (standard_version, vendor_id, functional_mode))
        for label in ("operations", "events", "props", "captureFormats", "imageFormats"):
            count = struct.unpack_from("<H", data, offset)[0]
            offset += 2 + count * 2
        for label in ("manufacturer", "model", "version", "serial"):
            text, offset = ptp_str(data, offset)
            print("  %s=%s" % (label, text))

        print("\nGetStorageIDs:")
        _, data = self._request(0x1004, want_data=True)
        count = struct.unpack_from("<I", data, 0)[0]
        storage_ids = list(struct.unpack_from("<%dI" % count, data, 4))
        print("  %s" % [hex(s) for s in storage_ids])
        for storage_id in storage_ids:
            _, data = self._request(0x1007, [storage_id, 0, 0xFFFFFFFF], want_data=True)
            count = struct.unpack_from("<I", data, 0)[0]
            handles = list(struct.unpack_from("<%dI" % count, data, 4)) if count else []
            print("  storage=%s objects=%d" % (hex(storage_id), len(handles)))

        print("\nProbe complete; session stays open for keepalive testing.")

    def keepalive(self, seconds):
        print("\nSending DeviceReady every %ds; Ctrl+C to stop" % seconds)
        try:
            while True:
                code, _ = self._request(0x90C8)
                print("[%s] DeviceReady -> 0x%04X" % (time.strftime("%H:%M:%S"), code))
                time.sleep(seconds)
        except (KeyboardInterrupt, EOFError, OSError) as exc:
            print("keepalive stopped: %s" % exc)

    def close(self):
        for conn in (self.evt, self.cmd):
            if conn:
                try:
                    conn.close()
                except OSError:
                    pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=15740)
    parser.add_argument("--guid", default="00112233445566778899aabbccddeeff")
    parser.add_argument("--name", default="N-Link-Probe")
    parser.add_argument("--keepalive", type=int, default=0)
    args = parser.parse_args()

    guid = bytes.fromhex(args.guid)
    probe = Probe(args.host, args.port, guid, args.name)
    try:
        probe.run()
        if args.keepalive > 0:
            probe.keepalive(args.keepalive)
    except Exception as exc:
        print("PROBE FAILED: %s" % exc)
        raise
    finally:
        probe.close()


if __name__ == "__main__":
    main()
