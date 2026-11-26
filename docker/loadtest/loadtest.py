"""Load test of the tamacat-monitor read APIs (T-503). Standard library only.

usage: python -I loadtest.py BASE_URL LOGIN_ID PASSWORD_FILE [CONCURRENCY] [REQUESTS_PER_ENDPOINT]
  e.g. python -I loadtest.py http://127.0.0.1:8080/monitor admin docker/secrets/tmon_admin_current_password.txt 10 100

Each worker thread has its own session. Prints latency percentiles per endpoint; no secrets are printed.
Use with the data of load-data-7.0.sql (server id 1 = the 7.0 test server).
"""
import concurrent.futures
import datetime
import http.cookiejar
import json
import re
import statistics
import sys
import time
import urllib.request

BASE, LOGIN = sys.argv[1].rstrip('/'), sys.argv[2]
PASSWORD = open(sys.argv[3], encoding='utf-8').read().strip()
CONCURRENCY = int(sys.argv[4]) if len(sys.argv) > 4 else 10
REQUESTS = int(sys.argv[5]) if len(sys.argv) > 5 else 100
SERVER = 1

now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
iso = lambda d: d.isoformat().replace('+00:00', 'Z')
ENDPOINTS = {
    'dashboard (all servers)': '/api/dashboard',
    'dashboard (server)': f'/api/dashboard/servers/{SERVER}',
    'problems (100 newest)': f'/api/zabbix/problems?server={SERVER}&limit=100',
    'problems (severity>=4)': f'/api/zabbix/problems?server={SERVER}&severity=4&limit=100',
    'alerts (31 days, 100)': f'/api/zabbix/alerts?server={SERVER}&from={iso(now - datetime.timedelta(days=31))}&to={iso(now)}&limit=100',
    'alerts (1 day, text)': f'/api/zabbix/alerts?server={SERVER}&from={iso(now - datetime.timedelta(days=1))}&to={iso(now)}&q=ops7&limit=100',
    'hosts (search)': f'/api/zabbix/hosts?server={SERVER}&q=load-1&limit=200',
    'items (host, latest values)': f'/api/zabbix/servers/{SERVER}/items?host=900000000123&limit=51',
    'items (search, latest values)': f'/api/zabbix/servers/{SERVER}/items?q=load%20item%205&limit=51',
}


def session():
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    csrf = re.search(r'"csrfToken":"([^"]+)"', op.open(BASE + '/login').read().decode()).group(1)
    req = urllib.request.Request(BASE + '/api/login', data=json.dumps({'loginId': LOGIN, 'password': PASSWORD}).encode(),
                                 headers={'Content-Type': 'application/json', 'X-CSRF-Token': csrf}, method='POST')
    op.open(req).read()
    return op


def worker(path, n):
    op = session()
    times, errors = [], 0
    for _ in range(n):
        t = time.perf_counter()
        try:
            r = op.open(BASE + path)
            r.read()
            if r.status != 200:
                errors += 1
        except Exception:
            errors += 1
        times.append((time.perf_counter() - t) * 1000)
    return times, errors


def main():
    print(f'concurrency={CONCURRENCY} requests/endpoint={REQUESTS} user={LOGIN}')
    print(f'{"endpoint":32} {"p50 ms":>8} {"p95 ms":>8} {"max ms":>8} {"req/s":>7} errors')
    for name, path in ENDPOINTS.items():
        per = max(1, REQUESTS // CONCURRENCY)
        start = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(CONCURRENCY) as ex:
            results = list(ex.map(lambda _: worker(path, per), range(CONCURRENCY)))
        wall = time.perf_counter() - start
        times = sorted(t for r in results for t in r[0])
        errors = sum(r[1] for r in results)
        p95 = times[int(len(times) * 0.95) - 1]
        print(f'{name:32} {statistics.median(times):8.0f} {p95:8.0f} {times[-1]:8.0f} {len(times) / wall:7.1f} {errors}')


main()
