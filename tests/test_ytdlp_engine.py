import json, sys, tempfile, unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
import media_engine as me
import ytdlp_engine as ye


def fmt(fid, ext, v=None, a=None, h=None, w=None, abr=None, tbr=None):
    return {"format_id": fid, "ext": ext, "url": "https://cdn.example.org/" + fid,
            "vcodec": v or "none", "acodec": a or "none", "height": h, "width": w, "abr": abr, "tbr": tbr}


FORMATS = [
    fmt("18", "mp4", "avc1", "mp4a", 360, 640, tbr=500),
    fmt("136", "mp4", "avc1", None, 720, 1280, tbr=1500),
    fmt("137", "mp4", "avc1", None, 1080, 1920, tbr=3000),
    fmt("248", "webm", "vp9", None, 1080, 1920, tbr=2500),
    fmt("140", "m4a", None, "mp4a", abr=128),
    fmt("251", "webm", None, "opus", abr=160),
]
INFO = {"title": "T", "duration": 10, "thumbnail": "https://i.example.org/t.jpg",
        "extractor_key": "Youtube", "formats": FORMATS}


class FakeYDL:
    mode = "ok"; calls = 0; info = INFO
    def __init__(self, opts): self.opts = opts
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def extract_info(self, url, download=False):
        FakeYDL.calls += 1
        if FakeYDL.mode == "unsupported": raise Exception("ERROR: Unsupported URL: " + url)
        if FakeYDL.mode == "403": raise Exception("HTTP Error 403: Forbidden")
        if FakeYDL.mode == "429": raise Exception("HTTP Error 429: Too Many Requests")
        if FakeYDL.mode == "flaky" and FakeYDL.calls < 3: raise Exception("timed out")
        return FakeYDL.info
    def download(self, urls):
        fid = self.opts["format"]
        tmpl = self.opts["outtmpl"].replace("%(id).40B", "abc").replace("%(ext)s", "mp4")
        for hook in self.opts["progress_hooks"]:
            hook({"status": "downloading", "downloaded_bytes": 50, "total_bytes": 100, "speed": 1024, "eta": 5})
        Path(tmpl).write_bytes(b"data-" + fid.encode())
        for hook in self.opts["progress_hooks"]:
            hook({"status": "finished", "filename": tmpl})


class CB:
    def __init__(self, cancel=False): self.cancel = cancel; self.events = []
    def isCancelled(self): return self.cancel
    def onProgress(self, p, s, d): self.events.append((p, s, d))


class Tests(unittest.TestCase):
    def setUp(self):
        ye._INFO_CACHE.clear()
        FakeYDL.mode = "ok"; FakeYDL.calls = 0; FakeYDL.info = INFO
        self._sleep = me.time.sleep; me.time.sleep = lambda s: None

    def tearDown(self): me.time.sleep = self._sleep

    def test_analyze_lists_only_found_formats(self):
        r = ye.analyze("https://youtu.be/x", factory=FakeYDL)
        self.assertEqual(r["heights"], [1080, 720, 360]); self.assertTrue(r["has_audio"])

    def test_high_and_low_ladder(self):
        fs = FORMATS + [fmt("313", "webm", "vp9", None, 2160, 3840), fmt("160", "mp4", "avc1", None, 144, 256)]
        FakeYDL.info = dict(INFO, formats=fs)
        self.assertEqual(ye.analyze("https://youtu.be/x", factory=FakeYDL)["heights"], [2160, 1080, 720, 360, 144])
        self.assertIn("2160p", me.VIDEO_QUALITIES)

    def test_select_merge_prefers_mp4_pair(self):
        p = ye.select_formats(FORMATS, "MP4", "best")
        self.assertEqual((p["video"]["format_id"], p["audio"]["format_id"], p["container"]), ("137", "140", "mp4"))

    def test_select_quality_limit_and_progressive(self):
        p = ye.select_formats(FORMATS, "MP4", "360p")
        self.assertEqual(p["video"]["format_id"], "18"); self.assertIsNone(p["audio"])

    def test_unavailable_quality(self):
        with self.assertRaises(me.DownloadFailure): ye.select_formats([FORMATS[1]], "MP4", "360p")

    def test_select_audio(self):
        self.assertEqual(ye.select_formats(FORMATS, "MP3", "best")["audio"]["format_id"], "251")

    def test_drm_filtered(self):
        drm = dict(FORMATS[2], has_drm=True)
        self.assertEqual(ye.select_formats([drm] + FORMATS, "MP4", "best")["video"]["format_id"], "137")

    def test_download_merge_hashes(self):
        with tempfile.TemporaryDirectory() as d:
            cb = CB(); out = json.loads(ye.download("https://youtu.be/x", "MP4", "best", d, cb, FakeYDL))
            self.assertEqual(out["kind"], "merge"); self.assertEqual(out["container"], "mp4")
            self.assertTrue(Path(out["audio_path"]).is_file())
            self.assertTrue(any(s == "progress" for _, s, _ in cb.events))

    def test_download_single_audio(self):
        with tempfile.TemporaryDirectory() as d:
            out = json.loads(ye.download("https://youtu.be/x", "MP3", "320k", d, CB(), FakeYDL))
            self.assertEqual(out["kind"], "single")

    def test_cancel(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(me.DownloadCancelled):
            ye.download("https://youtu.be/x", "MP4", "best", d, CB(cancel=True), FakeYDL)

    def test_unsupported(self):
        FakeYDL.mode = "unsupported"
        with self.assertRaisesRegex(me.DownloadFailure, "not supported"): ye.analyze("https://x.example.org/p", factory=FakeYDL)

    def test_403_tries_every_profile_then_reports_access_denied(self):
        FakeYDL.mode = "403"
        with self.assertRaises(me.AccessDenied): ye.analyze("https://x.example.org/p", factory=FakeYDL)
        self.assertEqual(FakeYDL.calls, len(ye.PROFILES))  # bounded, never infinite

    def test_403_recovers_with_fresh_extraction_on_next_profile(self):
        seen = []
        class Recovering(FakeYDL):
            def extract_info(self, url, download=False):
                seen.append(self.opts.get("extractor_args"))
                if len(seen) == 1: raise Exception("HTTP Error 403: Forbidden")
                return INFO
        with tempfile.TemporaryDirectory() as d:
            out = json.loads(ye.download("https://x.example.org/p", "MP4", "best", d, CB(), factory=Recovering))
        self.assertEqual(out["kind"], "merge")
        self.assertIsNone(seen[0]); self.assertEqual(seen[1], ye.PROFILES[1])

    def test_403_during_stream_download_reextracts_with_new_profile(self):
        calls = {"dl": 0}
        class Mid(FakeYDL):
            def download(self, urls):
                calls["dl"] += 1
                if calls["dl"] == 1: raise Exception("HTTP Error 403: Forbidden")
                return super().download(urls)
        with tempfile.TemporaryDirectory() as d:
            out = json.loads(ye.download("https://x.example.org/p", "MP3", "best", d, CB(), factory=Mid))
        self.assertEqual(out["kind"], "single")
        self.assertGreaterEqual(FakeYDL.calls, 2)  # metadata fetched again => fresh URLs

    def test_non_403_errors_do_not_walk_the_profile_ladder(self):
        FakeYDL.mode = "unsupported"
        with self.assertRaises(me.DownloadFailure): ye.analyze("https://x.example.org/p", factory=FakeYDL)
        self.assertEqual(FakeYDL.calls, 1)

    def test_429_not_retried(self):
        FakeYDL.mode = "429"
        with self.assertRaises(me.RateLimited): ye.analyze("https://x.example.org/p", factory=FakeYDL)
        self.assertEqual(FakeYDL.calls, 1)

    def test_transient_retry_then_success(self):
        FakeYDL.mode = "flaky"
        self.assertEqual(ye.analyze("https://x.example.org/p", factory=FakeYDL)["title"], "T")
        self.assertEqual(FakeYDL.calls, 3)

    def test_live_rejected(self):
        FakeYDL.info = dict(INFO, is_live=True)
        with self.assertRaisesRegex(me.DownloadFailure, "Live"): ye.analyze("https://x.example.org/p", factory=FakeYDL)

class ProgressAndCacheTests(unittest.TestCase):
    def setUp(self):
        ye._INFO_CACHE.clear(); FakeYDL.mode = "ok"; FakeYDL.calls = 0; FakeYDL.info = INFO
        self._sleep = me.time.sleep; me.time.sleep = lambda s: None
        self._interval = me.PROGRESS_INTERVAL; me.PROGRESS_INTERVAL = 0

    def tearDown(self):
        me.time.sleep = self._sleep; me.PROGRESS_INTERVAL = self._interval

    def test_progress_reports_bytes_total_speed_and_eta(self):
        with tempfile.TemporaryDirectory() as d:
            cb = CB(); ye.download("https://youtu.be/x", "MP3", "320k", d, cb, FakeYDL)
        stats = [json.loads(det) for p, st, det in cb.events if st == "progress"]
        self.assertTrue(stats)
        self.assertEqual((stats[0]["d"], stats[0]["t"], stats[0]["s"], stats[0]["e"]), (50, 100, 1024, 0))
        self.assertTrue(any(p == 50.0 for p, st, _ in cb.events if st == "progress"))

    def test_unknown_total_is_indeterminate_not_stuck_at_zero(self):
        t = ye._Tracker(CB(), {"m": 0.0}); cb = t.callback
        t.update("m", {"downloaded_bytes": 500})
        self.assertEqual(cb.events[-1][0], -1.0)
        self.assertEqual(json.loads(cb.events[-1][2])["d"], 500)

    def test_fragment_count_gives_percentage(self):
        t = ye._Tracker(CB(), {"m": 0.0}); cb = t.callback
        t.update("m", {"downloaded_bytes": 100, "fragment_index": 1, "fragment_count": 4})
        self.assertAlmostEqual(cb.events[-1][0], 25.0)

    def test_two_parallel_streams_are_combined(self):
        t = ye._Tracker(CB(), {"v": 300.0, "a": 100.0}); cb = t.callback
        t.update("v", {"downloaded_bytes": 150, "total_bytes": 300})
        self.assertAlmostEqual(cb.events[-1][0], 37.5)

    def test_analyze_result_is_reused_by_download(self):
        ye.analyze("https://youtu.be/x", factory=FakeYDL)
        self.assertEqual(FakeYDL.calls, 1)
        with tempfile.TemporaryDirectory() as d:
            ye.download("https://youtu.be/x", "MP4", "1080p", d, CB(), FakeYDL)
        self.assertEqual(FakeYDL.calls, 1)  # no second extraction

    def test_mp4_selection_always_targets_mp4_container(self):
        webm_only = [fmt("43", "webm", "vp9", "opus", 360, 640, tbr=400)]
        self.assertEqual(ye.select_formats(webm_only, "MP4", "best")["container"], "mp4")
        self.assertEqual(ye.select_formats(FORMATS, "MP4", "best")["audio"]["format_id"], "140")


if __name__ == "__main__":
    unittest.main()
