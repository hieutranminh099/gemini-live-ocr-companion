import asyncio
from contextlib import suppress
import json
import logging
import socket

DISCOVERY_PORT = 8767


def select_best_ip(addresses: list[str], target_ip: str | None = None) -> str:
    if not addresses:
        return '127.0.0.1'
    if target_ip and '.' in target_ip:
        prefix3 = target_ip.rsplit('.', 1)[0] + '.'
        for addr in addresses:
            if addr.startswith(prefix3):
                return addr
        prefix2 = '.'.join(target_ip.split('.')[:2]) + '.'
        for addr in addresses:
            if addr.startswith(prefix2):
                return addr
    return addresses[0]


def build_beacon(runtime, target_ip: str | None = None) -> dict:
    from .server import local_addresses
    addresses = local_addresses()
    best_ip = select_best_ip(addresses, target_ip)
    try:
        hostname = socket.gethostname() or 'Windows-PC'
    except OSError:
        hostname = 'Windows-PC'
    return dict(
        type='gemocr_beacon',
        protocol=1,
        name=hostname[:64],
        host=f'{best_ip}:{runtime.port}',
        addresses=addresses,
        port=runtime.port,
        pin=runtime.pin,
        lan_ticket=runtime.pairing.current_lan_ticket(),
    )


def broadcast_targets(addresses: list[str], port: int) -> list[tuple[str, int]]:
    targets = {('255.255.255.255', port)}
    for addr in addresses:
        parts = addr.split('.')
        if len(parts) == 4 and not addr.startswith('127.'):
            targets.add((f'{parts[0]}.{parts[1]}.{parts[2]}.255', port))
    return sorted(targets)


class LanDiscoveryProtocol(asyncio.DatagramProtocol):
    def __init__(self, runtime):
        self.runtime = runtime
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data: bytes, addr: tuple[str, int]):
        if not self.transport or len(data) > 4096:
            return
        try:
            payload = json.loads(data.decode('utf-8'))
            if not isinstance(payload, dict) or payload.get('type') != 'gemocr_discover':
                return
            beacon = build_beacon(self.runtime, target_ip=addr[0])
            raw = json.dumps(beacon, ensure_ascii=False).encode('utf-8')
            self.transport.sendto(raw, addr)
        except (ValueError, UnicodeDecodeError, OSError):
            pass


class LanDiscoveryService:
    def __init__(self, runtime, port: int = DISCOVERY_PORT, interval: float = 2.0):
        self.runtime = runtime
        self.port = port
        self.interval = interval
        self.transport = None
        self.protocol = None
        self._task: asyncio.Task | None = None
        self._closed = False

    async def start(self, bind_host: str = '0.0.0.0'):
        loop = asyncio.get_running_loop()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((bind_host, self.port))
            sock.setblocking(False)
            self.transport, self.protocol = await loop.create_datagram_endpoint(
                lambda: LanDiscoveryProtocol(self.runtime),
                sock=sock,
            )
        except OSError:
            sock.close()
            raise
        self.port = self.transport.get_extra_info('sockname')[1]
        self._task = asyncio.create_task(self._broadcast_loop())
        return self

    def send_broadcast_once(self):
        if not self.transport or self._closed:
            return
        from .server import local_addresses
        addresses = local_addresses()
        for target_host, target_port in broadcast_targets(addresses, self.port):
            beacon = build_beacon(self.runtime, target_ip=target_host)
            raw = json.dumps(beacon, ensure_ascii=False).encode('utf-8')
            with suppress(OSError):
                self.transport.sendto(raw, (target_host, target_port))

    async def _broadcast_loop(self):
        try:
            while not self._closed and not self.runtime.stop.is_set():
                self.send_broadcast_once()
                await asyncio.sleep(self.interval)
        except asyncio.CancelledError:
            pass
        except Exception:
            logging.debug('LAN discovery broadcast loop stopped', exc_info=True)

    async def close(self):
        self._closed = True
        if self._task:
            self._task.cancel()
            with suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        if self.transport:
            self.transport.close()
            self.transport = None
