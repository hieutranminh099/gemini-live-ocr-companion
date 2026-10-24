import asyncio
import json
import socket

from fastapi.testclient import TestClient

from app.discovery import LanDiscoveryService
from app.server import Runtime, device_app
from tests.test_system import FakeProvider


async def test_udp_discovery_probe_returns_beacon(tmp_path):
    runtime = Runtime(tmp_path, FakeProvider())
    runtime.port = 8765
    service = LanDiscoveryService(runtime, port=0, interval=60.0)
    await service.start(bind_host='127.0.0.1')
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(2.0)
        try:
            probe = json.dumps({'type': 'gemocr_discover', 'protocol': 1}).encode('utf-8')
            sock.sendto(probe, ('127.0.0.1', service.port))
            await asyncio.sleep(0.05)
            data, _ = sock.recvfrom(4096)
            beacon = json.loads(data.decode('utf-8'))
            assert beacon['type'] == 'gemocr_beacon'
            assert beacon['protocol'] == 1
            assert beacon['port'] == 8765
            assert beacon['pin'] == runtime.pin
            assert len(beacon['lan_ticket']) >= 16
        finally:
            sock.close()
    finally:
        await service.close()
        runtime.store.close()


def test_lan_ticket_pairing_and_auth_check(tmp_path):
    runtime = Runtime(tmp_path, FakeProvider())
    with TestClient(device_app(runtime)) as client:
        beacon_res = client.get('/v1/discover')
        assert beacon_res.status_code == 200
        beacon = beacon_res.json()
        assert beacon['pin'] == runtime.pin

        bad = client.post('/v1/pair', json={'name': 'Pixel 8', 'lan_ticket': 'invalid-ticket-value-12345'})
        assert bad.status_code == 403

        good = client.post('/v1/pair', json={'name': 'Pixel 8', 'lan_ticket': beacon['lan_ticket']})
        assert good.status_code == 200
        token = good.json()['token']
        assert len(token) >= 32

        check = client.get('/v1/auth-check', headers={'Authorization': f'Bearer {token}'})
        assert check.status_code == 200
        assert check.json()['status'] == 'ok'

        unauth = client.get('/v1/auth-check', headers={'Authorization': 'Bearer invalid'})
        assert unauth.status_code == 401
    runtime.store.close()
