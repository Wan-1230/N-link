#!/usr/bin/env python3
"""Mock Nikon PTP/IP camera used to debug NikonLink's WiFi connection path.

Run on a PC that is on the same WiFi/LAN as the phone, then point the app at
the PC (or let the built-in mDNS advertisement expose it as
"NIKON-LINK-MOCK"). Every PTP/IP packet the app sends is printed here, which
is exactly the data needed when the real camera returns "connection failed".

Usage:
    python tools/mock_nikon_ptp.py --port 15740 --advertise
"""

import argparse
import socket
import struct
import threading
import time


PACKET_NAMES = {
    0x01: "INIT_CMD_REQ",
    0x02: "INIT_CMD_ACK",
    0x03: "INIT_EVENT_REQ",
    0x04: "INIT_EVENT_ACK",
    0x05: "INIT_FAIL",
    0x06: "CMD_REQ",
    0x07: "CMD_RESP",
    0x08: "EVENT",
    0x09: "START_DATA",
    0x0A: "DATA",
    0x0B: "CANCEL",
    0x0C: "END_DATA",
    0x0D: "PING",
    0x0E: "PONG",
}

OP_NAMES = {
    0x1001: "GetDeviceInfo",
    0x1002: "OpenSession",
    0x1003: "CloseSession",
    0x1004: "GetStorageIDs",
    0x1005: "GetStorageInfo",
    0x1006: "GetNumObjects",
    0x1007: "GetObjectHandles",
    0x1008: "GetObjectInfo",
    0x1009: "GetObject",
    0x100A: "GetThumb",
    0x100E: "InitiateCapture",
    0x1014: "GetDevicePropDesc",
    0x1015: "GetDevicePropValue",
    0x1016: "SetDevicePropValue",
    0x101B: "GetPartialObject",
    0x90C1: "NikonAfDrive",
    0x90C7: "NikonCheckEvent",
    0x90C8: "NikonDeviceReady",
    0x9201: "NikonStartLiveView",
    0x9202: "NikonEndLiveView",
    0x9203: "NikonGetLiveViewImage",
    0x9204: "NikonMfDrive",
    0x9205: "NikonChangeAfArea",
    0x9206: "NikonAfDriveCancel",
    0x9207: "NikonInitiateCaptureRecInMedia",
    0x920A: "NikonStartMovieRecInCard",
    0x920B: "NikonEndMovieRec",
    0x920C: "NikonTerminateCapture",
}

RESPONSE_OK = 0x2001
RESPONSE_NOT_SUPPORTED = 0x2005
SESSION_ID = 0x55667788
CAMERA_GUID = bytes(range(16))
CAMERA_NAME = "NIKON-LINK-MOCK"

FAKE_JPEG = (
    b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    b"\xff\xd9"
)


def recv_exact(conn, size):
    data = b""
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def read_packet(conn):
    header = recv_exact(conn, 8)
    if header is None:
        return None, None
    length, ptype = struct.unpack("<II", header)
    if length < 8 or length > 64 * 1024 * 1024:
        raise ValueError("invalid packet length %d" % length)
    payload = recv_exact(conn, length - 8)
    if payload is None:
        return None, None
    return ptype, payload


def send_packet(conn, ptype, payload=b""):
    conn.sendall(struct.pack("<II", 8 + len(payload), ptype) + payload)


def cmd_response(tx, code, params=()):
    payload = struct.pack("<HI", code, tx) + struct.pack("<%dI" % len(params), *params)
    return struct.pack("<II", 8 + len(payload), 0x07) + payload


def start_data(tx, size):
    return struct.pack("<IIIII", 20, 0x09, tx, size, 0)


def data_packet(tx, data):
    return struct.pack("<III", 12 + len(data), 0x0A, tx) + data


def end_data(tx, data=b""):
    return struct.pack("<III", 12 + len(data), 0x0C, tx) + data


def send_data(conn, tx, data):
    conn.sendall(start_data(tx, len(data)))
    conn.sendall(data_packet(tx, data))
    conn.sendall(end_data(tx))
    conn.sendall(cmd_response(tx, RESPONSE_OK))


def ptp_str(text):
    encoded = text.encode("utf-16-le")
    return bytes([len(text)]) + encoded


def build_device_info(model):
    operations = [
        0x1001, 0x1002, 0x1003, 0x1004, 0x1005, 0x1006, 0x1007, 0x1008,
        0x1009, 0x100A, 0x100E, 0x1014, 0x1015, 0x1016, 0x101B,
        0x90C1, 0x90C8, 0x9201, 0x9202, 0x9203, 0x9204, 0x9205, 0x920A, 0x920B,
    ]
    events = []
    props = [0x5001, 0x5007, 0x500D, 0x500F, 0x5010]
    capture_formats = []
    image_formats = [0x3801, 0x300D]
    buf = struct.pack("<HIHHH", 100, 0x00000006, 100, 0, 0)
    for values in (operations, events, props, capture_formats, image_formats):
        buf += struct.pack("<H", len(values))
        buf += struct.pack("<%dH" % len(values), *values)
    buf += ptp_str("Nikon Corporation")
    buf += ptp_str(model)
    buf += ptp_str("1.00")
    buf += ptp_str("12345678")
    return buf


def build_object_info():
    filename = "DSC_0001.JPG"
    result = struct.pack(
        "<IHHIHHIIIIIIHII",
        0x00010001, 0x3801, 0, 12345, 0x3801, 0,
        160, 120, 6000, 4000, 24, 0, 0, 0, 0,
    )
    return result + bytes([len(filename)]) + filename.encode("utf-16-le")


def mdns_response(ip, port, camera_name):
    target = camera_name + "._ptp._tcp.local"
    service = "_ptp._tcp.local"
    host = camera_name + ".local"

    def name(value):
        return b"".join(bytes([len(part)]) + part.encode("ascii")
                        for part in value.split(".")) + b"\x00"

    answers = []
    rdata = name(target)
    answers.append(name(service) + struct.pack(">HHIH", 12, 1, 120, len(rdata)) + rdata)
    rdata = struct.pack(">HHH", 0, 0, port) + name(host)
    answers.append(name(target) + struct.pack(">HHIH", 33, 1, 120, len(rdata)) + rdata)
    rdata = socket.inet_aton(ip)
    answers.append(name(host) + struct.pack(">HHIH", 1, 1, 120, len(rdata)) + rdata)
    return struct.pack(">HHHHHH", 0, 0x8400, 0, len(answers), 0, 0) + b"".join(answers)


class MockCamera:
    def __init__(self, port, camera_name=CAMERA_NAME, advertise=False):
        self.port = port
        self.camera_name = camera_name
        self.advertise = advertise
        self.device_info = build_device_info(camera_name)
        self.object_info = build_object_info()
        self.event_conn = None
        self.running = True
        self.pending_set_tx = None
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", port))
        self.sock.listen(2)

    def start(self):
        threading.Thread(target=self._accept_loop, daemon=True).start()
        if self.advertise:
            threading.Thread(target=self._mdns_loop, daemon=True).start()

    def stop(self):
        self.running = False
        try:
            self.sock.close()
        except OSError:
            pass

    def _accept_loop(self):
        while self.running:
            try:
                conn, addr = self.sock.accept()
            except OSError:
                break
            self._command_conn(conn, addr)

    def _command_conn(self, conn, addr):
        print("== command connection from %s ==" % addr[0])
        try:
            ptype, payload = read_packet(conn)
            if ptype != 0x01:
                print("expected INIT_CMD_REQ, got %s" % ptype)
                conn.close()
                return
            guid = payload[:16].hex()
            name = self._utf16(payload[16:])
            version = payload[-4:].hex()
            print("INIT_CMD_REQ guid=%s name=%s version=%s" % (guid, name, version))
            send_packet(conn, 0x02, struct.pack("<I", SESSION_ID) + CAMERA_GUID +
                        (CAMERA_NAME + "\x00").encode("utf-16-le"))

            event_conn, event_addr = self.sock.accept()
            print("== event connection from %s ==" % event_addr[0])
            self.event_conn = event_conn
            ptype, payload = read_packet(event_conn)
            if ptype != 0x03:
                print("expected INIT_EVENT_REQ, got %s" % ptype)
                conn.close()
                return
            print("INIT_EVENT_REQ session=%s" % struct.unpack("<I", payload)[0])
            send_packet(event_conn, 0x04)
            threading.Thread(target=self._event_conn, args=(event_conn,), daemon=True).start()
            self._command_loop(conn)
        except Exception as exc:
            print("command connection error: %s" % exc)
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def _command_loop(self, conn):
        while self.running:
            ptype, payload = read_packet(conn)
            if ptype is None:
                print("command connection closed by client")
                break
            if ptype == 0x06:
                self._handle_command(conn, payload)
            elif ptype == 0x0C and self.pending_set_tx is not None:
                tx = self.pending_set_tx
                self.pending_set_tx = None
                print("SetDevicePropValue data phase complete (tx=%s)" % tx)
                conn.sendall(cmd_response(tx, RESPONSE_OK))
            elif ptype in (0x09, 0x0A):
                print("data phase packet: %s (%s bytes)" % (PACKET_NAMES[ptype], len(payload)))
            elif ptype in PACKET_NAMES:
                print("packet: %s (%s bytes)" % (PACKET_NAMES[ptype], len(payload)))
            else:
                print("unknown packet type 0x%04X (%s bytes)" % (ptype, len(payload)))

    def _event_conn(self, conn):
        while self.running:
            ptype, payload = read_packet(conn)
            if ptype is None:
                print("event connection closed by client")
                break
            if ptype == 0x0D:
                send_packet(conn, 0x0E)
            else:
                print("event packet: %s (%s bytes)" % (PACKET_NAMES.get(ptype, hex(ptype)), len(payload)))

    def _handle_command(self, conn, payload):
        data_phase, op = struct.unpack_from("<IH", payload, 0)
        tx = struct.unpack_from("<I", payload, 6)[0]
        params = list(struct.unpack_from("<%dI" % ((len(payload) - 10) // 4), payload, 10))
        op_name = OP_NAMES.get(op, hex(op))
        print("CMD_REQ op=0x%04X(%s) tx=%s phase=%s params=%s" % (op, op_name, tx, data_phase, [hex(p) for p in params]))

        if op == 0x1002:
            conn.sendall(cmd_response(tx, RESPONSE_OK))
        elif op == 0x1003:
            conn.sendall(cmd_response(tx, RESPONSE_OK))
        elif op == 0x90C8:
            conn.sendall(cmd_response(tx, RESPONSE_OK))
        elif op in (0x100E, 0x90C1, 0x9201, 0x9202, 0x920A, 0x920B):
            conn.sendall(cmd_response(tx, RESPONSE_OK))
        elif op == 0x1001:
            send_data(conn, tx, self.device_info)
        elif op == 0x1004:
            send_data(conn, tx, struct.pack("<II", 1, 0x00010001))
        elif op == 0x1005:
            send_data(conn, tx, struct.pack("<HHHQQI", 0, 1, 0, 0, 0, 0))
        elif op == 0x1007:
            send_data(conn, tx, struct.pack("<II", 1, 0x00010001))
        elif op == 0x1008:
            send_data(conn, tx, self.object_info)
        elif op in (0x1009, 0x100A, 0x101B):
            send_data(conn, tx, FAKE_JPEG)
        elif op == 0x1015:
            send_data(conn, tx, struct.pack("<I", 0x28))
        elif op == 0x1016:
            self.pending_set_tx = tx
        elif op == 0x9203:
            send_data(conn, tx, FAKE_JPEG)
        else:
            conn.sendall(cmd_response(tx, RESPONSE_NOT_SUPPORTED))

    def _mdns_loop(self):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", 5353))
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                            socket.inet_aton("224.0.0.251") + socket.inet_aton("0.0.0.0"))
        except OSError as exc:
            print("mDNS disabled: %s" % exc)
            return
        ip = self._local_ip()
        response = mdns_response(ip, self.port, self.camera_name)
        while self.running:
            try:
                sock.sendto(response, ("224.0.0.251", 5353))
            except OSError as exc:
                print("mDNS send failed: %s" % exc)
            sock.settimeout(1.5)
            try:
                data, addr = sock.recvfrom(4096)
                if b"_ptp" in data or b"_nikon" in data:
                    sock.sendto(response, addr)
            except socket.timeout:
                pass
            except OSError:
                break

    def _local_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except OSError:
            return "127.0.0.1"

    @staticmethod
    def _utf16(data):
        try:
            return data.split(b"\x00\x00", 1)[0].decode("utf-16-le")
        except Exception:
            return data.hex()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=15740)
    parser.add_argument("--name", default=CAMERA_NAME)
    parser.add_argument("--advertise", action="store_true",
                        help="advertise via mDNS so the app's WiFi scan finds this mock camera")
    args = parser.parse_args()

    camera = MockCamera(args.port, args.name, args.advertise)
    camera.start()
    print("Mock Nikon camera listening on :%d (name=%s)" % (args.port, args.name))
    if args.advertise:
        print("mDNS advertisement enabled; press Ctrl+C to stop")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        camera.stop()


if __name__ == "__main__":
    main()
