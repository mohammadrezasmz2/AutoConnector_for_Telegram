"""Offline regression tests for real MTProto proxy ranking.

Run: python3 -m unittest discover -s web -p 'test_proxy_rank.py' -v
Requires cryptography (used by mtproto_check).
"""
import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import mtproto_check
from build_site import build, check_all, history_key, load_history

SECRET = "00112233445566778899aabbccddeeff"


def proxy(host):
    return (host, 443, SECRET)


class ProxyRankingTests(unittest.TestCase):
    def test_unstable_fast_proxy_loses_to_reliable_proxy(self):
        fast, stable = proxy("fast.example.org"), proxy("stable.example.org")
        results = {fast: [70.0, None, None], stable: [185.0, 180.0, 190.0]}
        ranked, live = build([fast, stable], results, 3, 20, {})
        self.assertEqual([p["host"] for p in ranked],
                         ["stable.example.org", "fast.example.org"])
        self.assertGreater(live[1]["score"], live[0]["score"])
        self.assertEqual(ranked[0]["ok"], 3)
        self.assertEqual(ranked[0]["tries"], 3)

    def test_tail_latency_and_jitter_are_penalized(self):
        smooth, spiky = proxy("smooth.example.org"), proxy("spiky.example.org")
        results = {smooth: [180.0, 180.0, 180.0],
                   spiky: [80.0, 180.0, 390.0]}
        ranked, _ = build([spiky, smooth], results, 3, 10, {})
        self.assertEqual(ranked[0]["host"], "smooth.example.org")
        self.assertGreater(ranked[1]["jitter"], ranked[0]["jitter"])

    def test_failed_latest_scan_never_enters_live_list(self):
        failed = proxy("dead.example.org")
        ranked, live = build([failed], {failed: [None, None]}, 3, 10, {})
        self.assertEqual(ranked, [])
        self.assertEqual(live, [])

    def test_history_does_not_store_plaintext_proxy_secret(self):
        p = proxy("safe.example.org")
        cache = {}
        build([p], {p: [100.0, 105.0, 110.0]}, 3, 10, cache)
        self.assertIn(history_key(p), cache)
        self.assertNotIn(SECRET, json.dumps(cache))
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "history.json"
            path.write_text(json.dumps({"version": 1, "proxies": cache}), encoding="utf-8")
            self.assertEqual(load_history(path), cache)

    def test_historical_failures_penalize_a_lucky_scan(self):
        reliable, flaky = proxy("reliable.example.org"), proxy("flaky.example.org")
        hist = {}
        for _ in range(3):
            build([reliable, flaky],
                  {reliable: [180.0, 180.0, 180.0],
                   flaky: [None, None]},
                  3, 20, hist)
        ranked, _ = build([flaky, reliable],
                          {reliable: [185.0, 185.0, 185.0],
                           flaky: [80.0, 80.0, 80.0]},
                          3, 20, hist)
        self.assertEqual(ranked[0]["host"], "reliable.example.org")


class VerificationRetryTests(unittest.IsolatedAsyncioTestCase):
    async def test_first_handshake_failure_gets_second_chance(self):
        p = proxy("retried.example.org")
        answers = iter([None, 120.0, 115.0])

        async def simulated_check(host, port, secret, timeout):
            return next(answers)

        with patch.object(mtproto_check, "check", new=simulated_check):
            samples = await check_all([p], rounds=3, timeout=1.0, concurrency=1)
        self.assertEqual(samples[p], [None, 120.0, 115.0])

    async def test_permanent_handshake_failure_is_not_repeated_three_times(self):
        p = proxy("dead.example.org")
        calls = []

        async def simulated_check(*args):
            calls.append(True)
            return None

        with patch.object(mtproto_check, "check", new=simulated_check):
            samples = await check_all([p], rounds=3, timeout=1.0, concurrency=1)
        self.assertEqual(samples[p], [None, None])
        self.assertEqual(len(calls), 2)


if __name__ == "__main__":
    unittest.main()
