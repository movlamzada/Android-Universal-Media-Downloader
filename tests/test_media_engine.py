from __future__ import annotations

import hashlib
import io
import json
import sys
import tempfile
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

sys.path.insert(
    0,
    str(Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "python"),
)
import media_engine


class Callback:
    def __init__(self, cancel=False):
        self.cancel = cancel
        self.events = []

    def isCancelled(self):
        return self.cancel

    def onProgress(self, percent, stage, detail):
        self.events.append((percent, stage, detail))


class FakeResponse:
    def __init__(self, body: bytes, content_type="video/mp4", status=200, content_length=True):
        self._body = io.BytesIO(body)
        self.status = status
        self.headers = Message()
        self.headers["Content-Type"] = content_type
        if content_length:
            self.headers["Content-Length"] = str(len(body))
        self.closed = False

    def getcode(self):
        return self.status

    def read(self, size=-1):
        return self._body.read(size)

    def close(self):
        self.closed = True
        self._body.close()


class FakeOpener:
    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.calls = 0
        self.requests = []

    def open(self, request, timeout=None):
        self.calls += 1
        self.requests.append(request)
        if not self.outcomes:
            raise AssertionError("No fake response configured")
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


def mp4_bytes(payload=b"sample-video"):
    # Minimal ISO-BMFF ftyp signature for the engine's container sniff check.
    return b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isomiso2" + payload


def http_error(status, headers=None):
    return HTTPError(
        "https://cdn.example.org/media.mp4",
        status,
        "test response",
        headers or Message(),
        io.BytesIO(b"error"),
    )


class MediaEngineTests(unittest.TestCase):
    def setUp(self):
        self.dns = patch(
            "media_engine.socket.getaddrinfo",
            return_value=[(2, 1, 6, "", ("93.184.216.34", 443))],
        )
        self.dns.start()
        self.sleep = patch("media_engine.time.sleep")
        self.sleep.start()
        self.addCleanup(patch.stopall)

    def test_clear_hls_vod_manifest_is_accepted(self):
        body = (
            "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
            "#EXTINF:10,\nsegment-1.ts\n#EXTINF:5.5,\nsegment-2.ts\n#EXT-X-ENDLIST\n"
        ).encode()
        opener = FakeOpener([FakeResponse(body, "application/vnd.apple.mpegurl")])
        with tempfile.TemporaryDirectory() as temp:
            result = json.loads(media_engine.download_media(
                "https://cdn.example.org/vod/index.m3u8", "MP4", "720p", temp, Callback(), opener
            ))
        self.assertEqual(result["kind"], "adaptive")
        self.assertEqual(result["manifest"], "hls")
        self.assertEqual(result["url"], "https://cdn.example.org/vod/index.m3u8")
        self.assertEqual(result["duration_seconds"], 15.5)

    def test_hls_master_checks_child_playlist_for_encryption(self):
        master = (
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\n"
            "variant.m3u8\n"
        ).encode()
        child = (
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
            "#EXTINF:5,\nseg.ts\n#EXT-X-ENDLIST\n"
        ).encode()
        opener = FakeOpener([
            FakeResponse(master, "application/vnd.apple.mpegurl"),
            FakeResponse(child, "application/vnd.apple.mpegurl"),
        ])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(media_engine.DownloadFailure, "Encrypted HLS"):
                media_engine.download_media(
                    "https://cdn.example.org/vod/master.m3u8", "MP4", "720p", temp, Callback(), opener
                )
        self.assertEqual(opener.calls, 2)

    def test_live_hls_playlist_is_rejected(self):
        body = b"#EXTM3U\n#EXTINF:5,\nsegment.ts\n"
        opener = FakeOpener([FakeResponse(body, "application/vnd.apple.mpegurl")])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(media_engine.DownloadFailure, "Live HLS"):
                media_engine.download_media(
                    "https://cdn.example.org/live.m3u8", "MP4", "720p", temp, Callback(), opener
                )

    def test_clear_static_dash_manifest_is_accepted(self):
        body = b'<MPD type="static" mediaPresentationDuration="PT1M12.5S"><Period><AdaptationSet /></Period></MPD>'
        opener = FakeOpener([FakeResponse(body, "application/dash+xml")])
        with tempfile.TemporaryDirectory() as temp:
            result = json.loads(media_engine.download_media(
                "https://cdn.example.org/vod/manifest.mpd", "MP4", "720p", temp, Callback(), opener
            ))
        self.assertEqual(result["kind"], "adaptive")
        self.assertEqual(result["manifest"], "dash")
        self.assertEqual(result["duration_seconds"], 72.5)

    def test_dash_drm_and_dynamic_manifests_are_rejected(self):
        for payload, expected in (
            (b'<MPD type="static"><ContentProtection schemeIdUri="urn:uuid:test" /></MPD>', "DRM-protected"),
            (b'<MPD type="dynamic"><Period /></MPD>', "Live/dynamic DASH"),
        ):
            opener = FakeOpener([FakeResponse(payload, "application/dash+xml")])
            with tempfile.TemporaryDirectory() as temp, self.subTest(expected=expected):
                with self.assertRaisesRegex(media_engine.DownloadFailure, expected):
                    media_engine.download_media(
                        "https://cdn.example.org/vod/manifest.mpd", "MP4", "720p", temp, Callback(), opener
                    )

    def test_hls_manifest_rejects_non_https_segment_url(self):
        body = b"#EXTM3U\n#EXTINF:5,\nhttp://cdn.example.org/seg.ts\n#EXT-X-ENDLIST\n"
        opener = FakeOpener([FakeResponse(body, "application/vnd.apple.mpegurl")])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, "Only HTTPS"):
                media_engine.download_media(
                    "https://cdn.example.org/vod/playlist.m3u8", "MP4", "720p", temp, Callback(), opener
                )

    def test_dash_manifest_rejects_private_base_url(self):
        body = b'<MPD type="static"><BaseURL>https://10.0.0.4/private/</BaseURL></MPD>'
        opener = FakeOpener([FakeResponse(body, "application/dash+xml")])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, "Private"):
                media_engine.download_media(
                    "https://cdn.example.org/vod/manifest.mpd", "MP4", "720p", temp, Callback(), opener
                )

    def test_invalid_m3u8_payload_is_rejected(self):
        opener = FakeOpener([FakeResponse(b"<html>not a playlist</html>", "text/html")])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(media_engine.DownloadFailure, "valid HLS"):
                media_engine.download_media(
                    "https://cdn.example.org/not-a-playlist.m3u8", "MP4", "720p", temp, Callback(), opener
                )

    def test_https_direct_url_is_accepted(self):
        self.assertEqual(
            media_engine.validate_url(" https://cdn.example.org/path/movie.mp4?sig=x "),
            "https://cdn.example.org/path/movie.mp4?sig=x",
        )

    def test_http_and_non_web_schemes_are_rejected(self):
        for value in ("http://example.org/a.mp4", "file:///etc/passwd", "ftp://example.org/a", ""):
            with self.subTest(value=value), self.assertRaises(ValueError):
                media_engine.validate_url(value)

    def test_embedded_credentials_are_rejected(self):
        with self.assertRaises(ValueError):
            media_engine.validate_url("https://user:password@example.org/movie.mp4")

    def test_local_and_private_hosts_are_rejected(self):
        for value in (
            "https://localhost/a.mp4",
            "https://router.local/a.mp4",
            "https://127.0.0.1/a.mp4",
            "https://10.0.0.5/a.mp4",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                media_engine.validate_url(value)

    def test_control_characters_are_rejected(self):
        with self.assertRaises(ValueError):
            media_engine.validate_url("https://cdn.example.org/a.mp4\r\nX-Test: yes")

    def test_mp4_download_streams_hashes_and_commits_atomically(self):
        body = mp4_bytes()
        opener = FakeOpener([FakeResponse(body)])
        callback = Callback()
        with tempfile.TemporaryDirectory() as temp:
            raw = media_engine.download_media(
                "https://cdn.example.org/movie.mp4", "MP4", "1080p", temp, callback, opener
            )
            result = json.loads(raw)
            file_path = Path(result["path"])
            self.assertTrue(file_path.is_file())
            self.assertEqual(file_path.read_bytes(), body)
            self.assertEqual(result["bytes"], len(body))
            self.assertEqual(result["kind"], "single")
            self.assertFalse(list(Path(temp).glob("*.part")))
            self.assertTrue(any(event[1] == "download_finished" for event in callback.events))

    def test_mp4_rejects_html_payload(self):
        opener = FakeOpener([FakeResponse(b"<html>not media at all</html>", "application/octet-stream")])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(media_engine.IntegrityFailure):
                media_engine.download_media(
                    "https://cdn.example.org/file.bin", "MP4", "720p", temp, Callback(), opener
                )
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_webm_source_in_mp4_mode_keeps_its_real_extension_for_remux(self):
        body = b"\x1a\x45\xdf\xa3" + b"\x00" * 40
        opener = FakeOpener([FakeResponse(body, "video/webm")])
        with tempfile.TemporaryDirectory() as temp:
            result = json.loads(media_engine.download_media(
                "https://cdn.example.org/clip.webm", "MP4", "720p", temp, Callback(), opener))
            self.assertEqual(Path(result["path"]).suffix, ".webm")  # app remuxes it to a real .mp4

    def test_progress_detail_contains_sizes_speed_eta(self):
        info = json.loads(media_engine.progress_detail(10, 100, 5.0, 18.0))
        self.assertEqual(info, {"d": 10, "t": 100, "s": 5, "e": 18})
        self.assertEqual(json.loads(media_engine.progress_detail(1, 0))["e"], -1)

    def test_unknown_length_reports_indeterminate_progress(self):
        events = []
        class Cb:
            def isCancelled(self): return False
            def onProgress(self, p, s, d): events.append(p)
        media_engine._emit_progress(Cb(), -1, "progress", "{}")
        self.assertEqual(events, [-1.0])

    def test_html_page_is_not_treated_as_media(self):
        opener = FakeOpener([FakeResponse(b"<html>page</html>", "text/html")])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(media_engine.DownloadFailure, "web page"):
                media_engine.download_media(
                    "https://cdn.example.org/watch/123", "MP3", "192k", temp, Callback(), opener
                )

    def test_mp3_mode_accepts_video_source_for_native_conversion(self):
        body = mp4_bytes()
        opener = FakeOpener([FakeResponse(body, "video/mp4")])
        with tempfile.TemporaryDirectory() as temp:
            result = json.loads(media_engine.download_media(
                "https://cdn.example.org/source.mp4", "MP3", "192k", temp, Callback(), opener
            ))
            self.assertEqual(Path(result["path"]).suffix, ".mp4")
            self.assertEqual(Path(result["path"]).read_bytes(), body)

    def test_403_is_explicit_access_denial_and_not_retried(self):
        opener = FakeOpener([http_error(403)])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(media_engine.AccessDenied):
                media_engine.download_media(
                    "https://cdn.example.org/movie.mp4", "MP4", "1080p", temp, Callback(), opener
                )
        self.assertEqual(opener.calls, 1)

    def test_401_is_authentication_error_and_not_retried(self):
        opener = FakeOpener([http_error(401)])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(media_engine.AuthenticationRequired):
                media_engine.download_media(
                    "https://cdn.example.org/movie.mp4", "MP4", "1080p", temp, Callback(), opener
                )
        self.assertEqual(opener.calls, 1)

    def test_404_is_not_retried(self):
        opener = FakeOpener([http_error(404)])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(media_engine.DownloadFailure, "404"):
                media_engine.download_media(
                    "https://cdn.example.org/movie.mp4", "MP4", "1080p", temp, Callback(), opener
                )
        self.assertEqual(opener.calls, 1)

    def test_503_retries_then_succeeds(self):
        opener = FakeOpener([http_error(503), FakeResponse(mp4_bytes())])
        with tempfile.TemporaryDirectory() as temp:
            result = json.loads(media_engine.download_media(
                "https://cdn.example.org/movie.mp4", "MP4", "720p", temp, Callback(), opener
            ))
            self.assertTrue(Path(result["path"]).is_file())
        self.assertEqual(opener.calls, 2)

    def test_429_honors_retry_after_then_succeeds(self):
        headers = Message()
        headers["Retry-After"] = "3"
        opener = FakeOpener([http_error(429, headers), FakeResponse(mp4_bytes())])
        with tempfile.TemporaryDirectory() as temp:
            media_engine.download_media(
                "https://cdn.example.org/movie.mp4", "MP4", "360p", temp, Callback(), opener
            )
        self.assertEqual(opener.calls, 2)
        import media_engine as module
        module.time.sleep.assert_called_once_with(3.0)

    def test_long_retry_after_is_not_ignored_or_shortened(self):
        headers = Message()
        headers["Retry-After"] = "900"
        opener = FakeOpener([http_error(429, headers)])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(media_engine.RateLimited):
                media_engine.download_media(
                    "https://cdn.example.org/movie.mp4", "MP4", "360p", temp, Callback(), opener
                )
        self.assertEqual(opener.calls, 1)

    def test_incomplete_content_length_is_retried_and_temp_removed(self):
        truncated = FakeResponse(mp4_bytes())
        truncated.headers.replace_header("Content-Length", str(len(mp4_bytes()) + 100))
        opener = FakeOpener([truncated, FakeResponse(mp4_bytes())])
        with tempfile.TemporaryDirectory() as temp:
            result = json.loads(media_engine.download_media(
                "https://cdn.example.org/movie.mp4", "MP4", "1080p", temp, Callback(), opener
            ))
            self.assertTrue(Path(result["path"]).is_file())
            self.assertEqual(len(list(Path(temp).glob("*.part"))), 0)
        self.assertEqual(opener.calls, 2)

    def test_cancelled_transfer_does_not_leave_partial_file(self):
        opener = FakeOpener([FakeResponse(mp4_bytes())])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(media_engine.DownloadCancelled):
                media_engine.download_media(
                    "https://cdn.example.org/movie.mp4", "MP4", "720p", temp, Callback(cancel=True), opener
                )
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_download_size_limit_is_enforced(self):
        old_limit = media_engine.MAX_DOWNLOAD_BYTES
        media_engine.MAX_DOWNLOAD_BYTES = 8
        try:
            opener = FakeOpener([FakeResponse(mp4_bytes())])
            with tempfile.TemporaryDirectory() as temp:
                with self.assertRaises(media_engine.DownloadFailure):
                    media_engine.download_media(
                        "https://cdn.example.org/movie.mp4", "MP4", "720p", temp, Callback(), opener
                    )
                self.assertEqual(list(Path(temp).iterdir()), [])
        finally:
            media_engine.MAX_DOWNLOAD_BYTES = old_limit

    def test_invalid_quality_and_bitrate_are_rejected(self):
        opener = FakeOpener([])
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                media_engine.download_media(
                    "https://cdn.example.org/movie.mp4", "MP4", "999p", temp, Callback(), opener
                )
            with self.assertRaises(ValueError):
                media_engine.download_media(
                    "https://cdn.example.org/song.mp3", "MP3", "64k", temp, Callback(), opener
                )
        self.assertEqual(opener.calls, 0)

    def test_no_authorization_or_cookie_headers_are_injected(self):
        opener = FakeOpener([FakeResponse(mp4_bytes())])
        with tempfile.TemporaryDirectory() as temp:
            media_engine.download_media(
                "https://cdn.example.org/movie.mp4", "MP4", "1080p", temp, Callback(), opener
            )
        headers = {key.lower() for key, _ in opener.requests[0].header_items()}
        self.assertNotIn("authorization", headers)
        self.assertNotIn("cookie", headers)


if __name__ == "__main__":
    unittest.main(verbosity=2)
