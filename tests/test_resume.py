"""Resume (HTTP Range / If-Range) behaviour of the direct downloader."""
from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "python"))
import media_engine as me  # noqa: E402

URL = "https://cdn.example.com/video.mp4"
BODY = b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 8 + bytes(range(256)) * 40  # valid-looking MP4 header


class Cb:
    def isCancelled(self): return False
    def onProgress(self, *a): pass


class Resp:
    def __init__(self, body, status=200, headers=None, fail_after=None):
        self._b = io.BytesIO(body); self.status = status; self.fail_after = fail_after
        self.headers = Message()
        for k, v in (headers or {}).items():
            self.headers[k] = v
        self._read = 0
    def getcode(self): return self.status
    def read(self, n=-1):
        if self.fail_after is not None and self._read >= self.fail_after:
            raise ConnectionResetError("reset")
        data = self._b.read(n if self.fail_after is None else min(n, self.fail_after - self._read))
        self._read += len(data)
        return data
    def close(self): pass


class Server:
    """Range-capable fake server; first response may drop the connection mid-way."""
    def __init__(self, drop_at=None, support_range=True, etag='"v1"'):
        self.drop_at, self.support_range, self.etag = drop_at, support_range, etag
        self.requests = []
    def open(self, request, timeout=None):
        h = {k.lower(): v for k, v in request.header_items()}
        self.requests.append(h)
        base = {"Content-Type": "video/mp4", "ETag": self.etag}
        rng = h.get("range")
        if rng and self.support_range and h.get("if-range") == self.etag:
            start = int(rng.split("=")[1].rstrip("-"))
            part = BODY[start:]
            return Resp(part, 206, {**base, "Content-Length": str(len(part)),
                                    "Content-Range": f"bytes {start}-{len(BODY)-1}/{len(BODY)}"})
        drop, self.drop_at = self.drop_at, None
        return Resp(BODY, 200, {**base, "Content-Length": str(len(BODY))}, fail_after=drop)


class ResumeTests(unittest.TestCase):
    def setUp(self):
        patcher = patch.object(me, "_validate_resolved_host")
        patcher.start(); self.addCleanup(patcher.stop)

    def run_dl(self, server, d):
        with patch.object(me.time, "sleep"):
            return json.loads(me.download_media(URL, "MP4", "best", d, Cb(), opener=server))

    def test_connection_drop_resumes_with_range_and_matches_full_file(self):
        with tempfile.TemporaryDirectory() as d:
            srv = Server(drop_at=1000)
            out = self.run_dl(srv, d)
            self.assertEqual(Path(out["path"]).read_bytes(), BODY)
            self.assertEqual(out["bytes"], len(BODY))
            self.assertEqual(len(srv.requests), 2)
            self.assertEqual(srv.requests[1]["range"], "bytes=1000-")
            self.assertEqual(srv.requests[1]["if-range"], '"v1"')
            self.assertEqual([p.name for p in Path(d).glob("*.part")], [])
            self.assertEqual([p.name for p in Path(d).glob("*.meta")], [])

    def test_server_without_range_restarts_cleanly_not_corrupt(self):
        with tempfile.TemporaryDirectory() as d:
            srv = Server(drop_at=500, support_range=False)
            out = self.run_dl(srv, d)
            self.assertEqual(Path(out["path"]).read_bytes(), BODY)  # no duplicated prefix

    def test_changed_resource_restarts_instead_of_mixing_versions(self):
        with tempfile.TemporaryDirectory() as d:
            part, meta = me._part_paths(Path(d), URL)
            part.write_bytes(BODY[:300]); meta.write_text(json.dumps({"etag": '"old"', "last_modified": ""}))
            srv = Server(etag='"new"')
            out = self.run_dl(srv, d)
            self.assertEqual(Path(out["path"]).read_bytes(), BODY)

    def test_partial_without_validator_is_discarded(self):
        with tempfile.TemporaryDirectory() as d:
            part, meta = me._part_paths(Path(d), URL)
            part.write_bytes(b"garbage-prefix")
            srv = Server()
            out = self.run_dl(srv, d)
            self.assertNotIn("range", srv.requests[0])
            self.assertEqual(Path(out["path"]).read_bytes(), BODY)

    def test_416_restarts_from_zero(self):
        class S416(Server):
            def open(self, request, timeout=None):
                if request.get_header("Range"):
                    self.requests.append({}); raise HTTPError(URL, 416, "bad range", Message(), io.BytesIO(b""))
                return super().open(request, timeout)
        with tempfile.TemporaryDirectory() as d:
            part, meta = me._part_paths(Path(d), URL)
            part.write_bytes(BODY[:100]); meta.write_text(json.dumps({"etag": '"v1"', "last_modified": ""}))
            out = self.run_dl(S416(), d)
            self.assertEqual(Path(out["path"]).read_bytes(), BODY)

    def test_exhausted_retries_keep_partial_for_later_resume(self):
        calls = {"n": 0}
        class Flaky(Server):
            def open(self, request, timeout=None):
                calls["n"] += 1
                if calls["n"] == 1:
                    return Resp(BODY, 200, {"Content-Type": "video/mp4", "ETag": '"v1"', "Content-Length": str(len(BODY))}, fail_after=400)
                raise HTTPError(URL, 503, "busy", Message(), io.BytesIO(b""))
        with tempfile.TemporaryDirectory() as d:
            with patch.object(me.time, "sleep"):
                with self.assertRaises(me.DownloadFailure):
                    me.download_media(URL, "MP4", "best", d, Cb(), opener=Flaky())
            part, _ = me._part_paths(Path(d).resolve(), URL)
            self.assertTrue(part.is_file()); self.assertEqual(part.stat().st_size, 400)
            self.assertEqual(list(Path(d).glob("media-*")), [])  # never exposes a final file


if __name__ == "__main__":
    unittest.main()
