#!/usr/bin/env python3
"""Lokální regrese paměti: skutečné API/worker JVM, izolovaný Postgres a cgroups v2.

Nevolá stores, AI ani kanály; potřebuje Docker a :server:app:installDist.
--compare-old přidá kontrolní běh se starým ZGC/heapem, obě varianty mají stejný cgroup limit.
"""

import argparse
import concurrent.futures
import http.client
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--dist",
    type=pathlib.Path,
    default=pathlib.Path(__file__).resolve().parents[2]
    / "server/app/build/install/appreviewzz",
)
parser.add_argument("--compare-old", action="store_true")
parser.add_argument("--output", type=pathlib.Path)
cli = parser.parse_args()
DIST = str(cli.dist.resolve())
if not (cli.dist / "bin/appreviewzz").is_file():
    parser.error("Nejdřív spusť ./gradlew :server:app:installDist")
PREFIX = "appreviewzz-memory-" + uuid.uuid4().hex[:8]
NETWORK = PREFIX + "-net"
created = []
results = {}


def docker(*args):
    return subprocess.check_output(
        ["docker", *args], stderr=subprocess.PIPE, text=True
    ).strip()


def run(name, *args):
    docker("run", "-d", "--name", name, *args)
    created.append(name)
    return name


def port(name, n):
    d = json.loads(docker("inspect", name))[0]
    return int(d["NetworkSettings"]["Ports"][str(n) + "/tcp"][0]["HostPort"])


def get(port, path):
    return (
        urllib.request.urlopen("http://127.0.0.1:" + str(port) + path, timeout=10)
        .read()
        .decode()
    )


def wait_ready(name):
    p = port(name, 8080)
    last_error = None
    for _ in range(120):
        try:
            if "UP" in get(p, "/health/ready"):
                return
        except (
            urllib.error.URLError,
            TimeoutError,
            ConnectionError,
            http.client.HTTPException,
        ) as error:
            last_error = error
        time.sleep(0.5)
    raise RuntimeError(
        name + " failed startup: " + docker("logs", name)[-3000:]
    ) from last_error


def snapshot(name):
    text = get(port(name, 8081), "/metrics")
    metrics = [
        (line.split(" ", 1)[0], float(line.rsplit(" ", 1)[1]))
        for line in text.splitlines()
        if line and not line.startswith("#")
    ]

    def total(prefix, tag=None):
        return sum(
            v
            for k, v in metrics
            if k.startswith(prefix) and (not tag or tag in k) and v >= 0
        )

    out = {
        "heap_used": total("jvm_memory_used_bytes", 'area="heap"'),
        "heap_committed": total("jvm_memory_committed_bytes", 'area="heap"'),
        "heap_max": total("jvm_memory_max_bytes", 'area="heap"'),
        "live_after_gc": total("jvm_gc_live_data_size_bytes"),
        "gc_pauses": total("jvm_gc_pause_seconds_count"),
        "gc_cycles": total("jvm_gc_concurrent_phase_time_seconds_count"),
        "threads": total("jvm_threads_live_threads"),
        "http_series": sum(
            k.startswith("ktor_http_server_requests_seconds_count") for k, v in metrics
        ),
    }
    for key in [
        "memory.current",
        "memory.swap.current",
        "memory.max",
        "memory.swap.max",
    ]:
        out[key] = int(docker("exec", name, "cat", "/sys/fs/cgroup/" + key))
    stat = dict(
        line.split()
        for line in docker(
            "exec", name, "cat", "/sys/fs/cgroup/memory.stat"
        ).splitlines()
    )
    out["working_set"] = out["memory.current"] - int(stat["inactive_file"])
    out["anon_and_shmem"] = int(stat["anon"]) + int(stat["shmem"])
    state = json.loads(docker("inspect", name))[0]
    out["restarts"] = state["RestartCount"]
    out["oom_killed"] = state["State"]["OOMKilled"]
    return out


def load(name, requests):
    p = port(name, 8080)

    def worker(n):
        conn = http.client.HTTPConnection("127.0.0.1", p, timeout=20)
        for i in range(n):
            path = "/health/live" if i % 2 else "/health/ready"
            conn.request("GET", path)
            response = conn.getresponse()
            response.read()
            if response.status != 200:
                raise RuntimeError(str(response.status))
        conn.close()

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(worker, [requests // 8] * 8))


try:
    docker("network", "create", NETWORK)
    db = run(
        PREFIX + "-db",
        "--network",
        NETWORK,
        "--network-alias",
        "postgres",
        "-e",
        "POSTGRES_USER=appreviewzz",
        "-e",
        "POSTGRES_DB=appreviewzz",
        "-e",
        "POSTGRES_HOST_AUTH_METHOD=trust",
        "postgres:17-alpine",
    )
    for _ in range(60):
        try:
            docker("exec", db, "pg_isready", "-U", "appreviewzz")
            break
        except subprocess.CalledProcessError:
            time.sleep(0.5)
    for variant in ["old", "fixed"] if cli.compare_old else ["fixed"]:
        roles = {}
        for role in ["api", "worker"]:
            options = "-Dlogback.configurationFile=logback-json.xml"
            if variant == "old":
                options += " -XX:-UseG1GC -XX:+UseZGC -Xms128m -Xmx6073352192"
            args = [
                "--network",
                NETWORK,
                "--memory=1g",
                "--memory-swap=1g",
                "-p",
                "127.0.0.1::8080",
                "-p",
                "127.0.0.1::8081",
                "-v",
                DIST + ":/opt/app:ro",
                "-e",
                "APPREVIEWZZ_ROLE=" + role,
                "-e",
                "APPREVIEWZZ_ENV=local",
                "-e",
                "DATABASE_URL=jdbc:postgresql://postgres:5432/appreviewzz",
                "-e",
                "DATABASE_USER=appreviewzz",
                "-e",
                "DATABASE_PASSWORD=memory-validation",
                "-e",
                "DATABASE_MIGRATE_ON_START=" + str(role == "api").lower(),
                "-e",
                "VAULT_KEK_URI=local:///tmp/memory-keyset",
                "-e",
                "AI_PROVIDER=none",
                "-e",
                "RATE_LIMIT_ENABLED=false",
                "-e",
                "JAVA_OPTS=" + options,
                "--entrypoint",
                "/opt/app/bin/appreviewzz",
                "eclipse-temurin:21-jre",
            ]
            name = run(PREFIX + "-" + variant + "-" + role, *args)
            wait_ready(name)
            roles[role] = name
        data = {
            "startup": {role: snapshot(name) for role, name in roles.items()},
            "cycles": [],
        }
        print(variant, "startup", json.dumps(data["startup"]), flush=True)
        for cycle in range(3):
            start = time.monotonic()
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                list(pool.map(lambda name: load(name, 10000), roles.values()))
            point = {role: snapshot(name) for role, name in roles.items()}
            data["cycles"].append(point)
            print(
                variant,
                "cycle",
                cycle + 1,
                "seconds",
                round(time.monotonic() - start, 1),
                json.dumps(point),
                flush=True,
            )
        print(variant, "waiting 75s for idle collection", flush=True)
        time.sleep(75)
        data["idle"] = {role: snapshot(name) for role, name in roles.items()}
        print(variant, "idle", json.dumps(data["idle"]), flush=True)
        results[variant] = data
        for role, name in roles.items():
            docker("rm", "-f", name)
            created.remove(name)
    for role, point in results["fixed"]["idle"].items():
        assert point["heap_max"] == 512 * 1048576, point
        assert point["heap_used"] < 128 * 1048576, point
        assert point["memory.swap.current"] == point["memory.swap.max"] == 0, point
        assert point["memory.max"] == 1024 * 1048576, point
        assert point["restarts"] == 0 and not point["oom_killed"], point
    if cli.output:
        cli.output.write_text(json.dumps(results, indent=2) + "\n")
    print(
        "PASS: both roles stayed within heap/cgroup budgets, reclaimed transient allocations and used no swap",
        flush=True,
    )
finally:
    for name in reversed(created):
        subprocess.run(
            ["docker", "rm", "-f", name],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    subprocess.run(
        ["docker", "network", "rm", NETWORK],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
