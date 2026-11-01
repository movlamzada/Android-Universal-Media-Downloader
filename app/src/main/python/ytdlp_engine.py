"""yt-dlp extractor integration (analysis, format selection, download).

Uses only yt-dlp's public API. It performs no authentication forging, token
generation, proxy rotation or anti-bot automation, and it refuses DRM-protected
formats and live streams. Muxing is NOT done here (no ffmpeg binary on Android):
when video and audio are separate streams both files are downloaded and
returned so MediaWorker can merge them with FFmpegKit.
"""

from __future__ import annotations

import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable

import media_engine as me

LADDER = (4320, 2160, 1440, 1080, 720, 480, 360, 240, 144)
MAX_ATTEMPTS = 3
CACHE_TTL_SECONDS = 600
# url -> (timestamp, profile index that worked, extracted info); lets download() reuse analyze().
_INFO_CACHE: dict[str, tuple[float, int, dict]] = {}
_TRANSIENT_MARKERS = (
    "timed out", "timeout", "connection reset", "connection aborted", "temporary failure",
    "name resolution", "remote end closed", "incomplete read", "http error 500",
    "http error 502", "http error 503", "http error 504", "network is unreachable",
)


def _default_factory(opts: dict):
    import yt_dlp  # imported lazily so unit tests can stub it
    return yt_dlp.YoutubeDL(opts)


# Public yt-dlp extractor profiles tried in order when a source answers HTTP 403.
# Each retry re-extracts the media (fresh, correctly signed URLs). Nothing here forges
# tokens or attestation; these are documented yt-dlp client selections.
PROFILES: tuple[dict, ...] = (
    {},
    {"youtube": {"player_client": ["android_vr"]}},
    {"youtube": {"player_client": ["tv"]}},
    {"youtube": {"player_client": ["web_safari"]}},
)


def _base_opts(profile: dict | None = None) -> dict:
    opts = {
        "quiet": True, "no_warnings": True, "noplaylist": True, "cachedir": False,
        "socket_timeout": 30, "retries": 3, "extractor_retries": 2,
        "fragment_retries": 5, "continuedl": True, "noprogress": True,
        "check_formats": False, "ignoreconfig": True,
        "concurrent_fragment_downloads": 8, "buffersize": 1 << 16,
    }
    if profile:
        opts["extractor_args"] = profile
    return opts


def _short_side(fmt: dict) -> int:
    sides = [int(v) for v in (fmt.get("width"), fmt.get("height")) if v]
    return min(sides) if sides else 0


def _has_video(fmt: dict) -> bool:
    return fmt.get("vcodec") not in (None, "none")


def _has_audio(fmt: dict) -> bool:
    return fmt.get("acodec") not in (None, "none")


def _usable(fmt: dict) -> bool:
    if not fmt.get("url") or fmt.get("has_drm"):
        return False
    if fmt.get("protocol") in {"mhtml", "rtsp", "rtmp", "rtmpe"} or fmt.get("ext") == "mhtml":
        return False
    if "storyboard" in str(fmt.get("format_note", "")).lower():
        return False
    return _has_video(fmt) or _has_audio(fmt) or (fmt.get("vcodec") is None and fmt.get("acodec") is None)


def _map_error(error: BaseException) -> Exception:
    text = str(error)
    low = text.lower()
    if "unsupported url" in low:
        return me.DownloadFailure("This website/URL is not supported by the bundled yt-dlp version.")
    if "drm" in low:
        return me.DownloadFailure("This media is DRM-protected and cannot be downloaded.")
    if "http error 429" in low or "too many requests" in low:
        return me.RateLimited("The site is rate-limiting requests. Wait and try again later.")
    if any(k in low for k in ("sign in", "log in", "login", "confirm you", "not a bot", "private video",
                              "members-only", "age-restricted", "http error 401")):
        return me.AuthenticationRequired("This media requires sign-in or verification. The app does not bypass it.")
    if "http error 403" in low or "forbidden" in low:
        return me.AccessDenied("The site denied access (HTTP 403). The link may have expired or be restricted.")
    if "http error 404" in low or "not available" in low or "unavailable" in low or "removed" in low:
        return me.DownloadFailure("The media is unavailable or was removed.")
    if "live" in low and "stream" in low:
        return me.DownloadFailure("Live streams are not supported.")
    return me.DownloadFailure(text.replace("ERROR: ", "")[:400] or "Extraction failed.")


def _is_transient(error: BaseException) -> bool:
    low = str(error).lower()
    return any(m in low for m in _TRANSIENT_MARKERS)


def _run(fn: Callable[[], Any], callback: Any, sleep: Callable[[float], None] = time.sleep) -> Any:
    """Run fn with exponential backoff for transient network errors only."""
    last: BaseException | None = None
    for attempt in range(MAX_ATTEMPTS):
        if me._is_cancelled(callback):
            raise me.DownloadCancelled("Download cancelled by user.")
        try:
            return fn()
        except me.DownloadCancelled:
            raise
        except me.DownloadFailure:
            raise
        except BaseException as error:  # noqa: BLE001 - yt-dlp raises many types
            if me._is_cancelled(callback):
                raise me.DownloadCancelled("Download cancelled by user.") from error
            last = error
            if not _is_transient(error) or attempt + 1 >= MAX_ATTEMPTS:
                raise _map_error(error) from error
            sleep(me._backoff_seconds(attempt + 1))
    raise _map_error(last or RuntimeError("failed"))


def _extract(url: str, factory: Callable, callback: Any, profile: dict | None = None) -> dict:
    def go() -> dict:
        with factory(_base_opts(profile)) as ydl:
            info = ydl.extract_info(url, download=False)
        if info is None:
            raise me.DownloadFailure("No media information was returned.")
        if info.get("_type") == "playlist":
            entries = [e for e in (info.get("entries") or []) if e]
            if not entries:
                raise me.DownloadFailure("The playlist contains no downloadable item.")
            info = entries[0]
        if info.get("is_live") or info.get("live_status") in {"is_live", "is_upcoming"}:
            raise me.DownloadFailure("Live streams are not supported.")
        return info
    return _run(go, callback)


def analyze(url: str, callback: Any = None, factory: Callable | None = None) -> dict:
    factory = factory or _default_factory
    info = None
    used = 0
    for index, profile in enumerate(PROFILES):
        try:
            info = _extract(url, factory, callback, profile)
            used = index
            break
        except me.AccessDenied:
            if index + 1 >= len(PROFILES):
                raise
    _INFO_CACHE[url] = (time.time(), used, info)
    formats = [f for f in (info.get("formats") or []) if _usable(f)]
    if not formats and info.get("url"):
        formats = [{"url": info["url"], "ext": info.get("ext"), "format_id": "0",
                    "vcodec": info.get("vcodec", "unknown"), "acodec": info.get("acodec", "unknown"),
                    "width": info.get("width"), "height": info.get("height")}]
    if not formats:
        raise me.DownloadFailure("No downloadable (non-DRM) formats were found.")
    shorts = [_short_side(f) for f in formats if _has_video(f) and _short_side(f)]
    offered = [h for h in LADDER if any(h * 0.85 <= s <= h * 1.1 for s in shorts)]
    return {
        "title": str(info.get("title") or "media"),
        "duration": float(info.get("duration") or 0),
        "thumbnail": str(info.get("thumbnail") or ""),
        "extractor": str(info.get("extractor_key") or info.get("extractor") or ""),
        "has_video": any(_has_video(f) for f in formats),
        "has_audio": any(_has_audio(f) for f in formats),
        "heights": offered,
        "max_height": max(shorts) if shorts else 0,
    }


def select_formats(formats: list[dict], mode: str, quality: str) -> dict:
    """Pick the format(s) to download. Returns {'video','audio','container'}."""
    fmts = [f for f in formats if _usable(f)]
    audio_only = [f for f in fmts if _has_audio(f) and not _has_video(f)]
    with_audio = [f for f in fmts if _has_audio(f)]
    if mode == "MP3":
        pool = audio_only or with_audio
        if not pool:
            raise me.DownloadFailure("No audio stream is available for this media.")
        best = max(pool, key=lambda f: (f.get("abr") or f.get("tbr") or 0, f.get("asr") or 0))
        return {"video": None, "audio": best, "container": None}

    videos = [f for f in fmts if _has_video(f)]
    if quality != "best":
        limit = int(quality.rstrip("p")) * 1.1
        videos = [f for f in videos if _short_side(f) <= limit]
    if not videos:
        raise me.DownloadFailure("The requested video quality is not available for this media.")

    def is_avc(f: dict) -> int:
        return 1 if str(f.get("vcodec") or "").lower().startswith(("avc", "h264")) else 0

    def vkey(f: dict) -> tuple:
        return (_short_side(f), is_avc(f), 1 if f.get("ext") == "mp4" else 0, f.get("tbr") or 0)

    progressive = [f for f in videos if _has_audio(f)]
    video_only = [f for f in videos if not _has_audio(f)]
    best_prog = max(progressive, key=vkey) if progressive else None
    best_vo = max(video_only, key=vkey) if video_only else None
    if best_vo and audio_only and (not best_prog or _short_side(best_vo) > _short_side(best_prog)):
        # AAC/M4A audio is preferred: it goes into MP4 without re-encoding.
        mp4_audio = [f for f in audio_only if f.get("ext") in {"m4a", "mp4"}]
        audio = max(mp4_audio or audio_only, key=lambda f: f.get("abr") or f.get("tbr") or 0)
        return {"video": best_vo, "audio": audio, "container": "mp4"}
    chosen = best_prog or best_vo
    return {"video": chosen, "audio": None, "container": "mp4"}


class _Tracker:
    """Aggregates parallel stream downloads into one progress figure (bytes, speed, ETA)."""

    def __init__(self, callback: Any, estimates: dict[str, float], base: float = 0.0, span: float = 100.0):
        self.callback, self.base, self.span = callback, base, span
        self.lock = threading.Lock()
        self.abort = threading.Event()
        self.done = {tag: 0.0 for tag in estimates}
        self.total = dict(estimates)
        self.speed = {tag: 0.0 for tag in estimates}
        self.last_emit = 0.0

    def update(self, tag: str, d: dict) -> None:
        if me._is_cancelled(self.callback) or self.abort.is_set():
            raise me.DownloadCancelled("Download cancelled by user.")
        done = float(d.get("downloaded_bytes") or 0)
        total = float(d.get("total_bytes") or d.get("total_bytes_estimate") or 0)
        count, index = d.get("fragment_count"), d.get("fragment_index")
        if not total and count and index and done:
            total = done * float(count) / max(float(index), 1.0)  # fragmented HLS/DASH: extrapolate
        with self.lock:
            self.done[tag] = done
            if total:
                self.total[tag] = total
            self.speed[tag] = float(d.get("speed") or 0)
            now = time.monotonic()
            if now - self.last_emit < me.PROGRESS_INTERVAL:
                return
            self.last_emit = now
            self._emit()

    def finish(self, tag: str) -> None:
        with self.lock:
            if self.total.get(tag):
                self.done[tag] = self.total[tag]
            else:
                self.total[tag] = self.done[tag]

    def _emit(self) -> None:
        done, total = sum(self.done.values()), sum(self.total.values())
        known = all(v > 0 for v in self.total.values())
        speed = sum(self.speed.values())
        fraction = min(done / total, 1.0) if (known and total) else None
        percent = self.base + self.span * fraction if fraction is not None else -1.0
        eta = (total - done) / speed if (known and speed > 0) else -1.0
        me._emit_progress(self.callback, min(percent, 99.0) if percent >= 0 else -1.0, "progress",
                          me.progress_detail(done, total if known else 0, speed, eta))


def _download_format(url: str, fmt: dict, directory: Path, tag: str, tracker: _Tracker,
                     callback: Any, factory: Callable, profile: dict | None = None) -> Path:
    produced: list[str] = []

    def hook(d: dict) -> None:
        if d.get("status") == "downloading":
            tracker.update(tag, d)
        elif d.get("status") == "finished" and d.get("filename"):
            produced.append(d["filename"])

    opts = _base_opts(profile)
    opts.update({
        "format": str(fmt.get("format_id")),
        "outtmpl": str(directory / f"{tag}-%(id).40B.%(ext)s"),
        "progress_hooks": [hook],
        "max_filesize": me.MAX_DOWNLOAD_BYTES,
        "restrictfilenames": True, "overwrites": True,
    })

    def go() -> None:
        with factory(opts) as ydl:
            ydl.download([url])

    _run(go, callback)
    path = Path(produced[-1]) if produced else None
    if path is None or not path.is_file() or path.stat().st_size <= 0:
        raise me.DownloadFailure("yt-dlp finished without producing a valid file.")
    tracker.finish(tag)
    return path


def download(url: str, mode: str, quality: str, work_dir: str, callback: Any,
             factory: Callable | None = None, profiles: tuple[dict, ...] | None = None) -> str:
    """Download with 403 recovery: on HTTP 403 re-extract with the next profile (fresh URLs)."""
    factory = factory or _default_factory
    directory = Path(work_dir)
    directory.mkdir(parents=True, exist_ok=True)
    me._emit_progress(callback, 0, "analyzing", "")
    ladder = profiles or PROFILES
    cached = _INFO_CACHE.pop(url, None)
    if cached and time.time() - cached[0] > CACHE_TTL_SECONDS:
        cached = None
    start = cached[1] if (cached and cached[1] < len(ladder) and profiles is None) else 0
    last: me.AccessDenied | None = None
    for index in range(start, len(ladder)):
        if me._is_cancelled(callback):
            raise me.DownloadCancelled("Download cancelled by user.")
        info = cached[2] if (cached and index == start and profiles is None) else None
        try:
            return _download_with(url, mode, quality, directory, callback, factory, ladder[index], info)
        except me.AccessDenied as denied:
            last = denied
            if index + 1 < len(ladder):
                me._emit_progress(callback, 0, "retry_403", f"{index + 1}")
    raise last or me.AccessDenied("The site denied access (HTTP 403).")


def _download_with(url: str, mode: str, quality: str, directory: Path, callback: Any,
                   factory: Callable, profile: dict, info: dict | None = None) -> str:
    info = info or _extract(url, factory, callback, profile)
    formats = info.get("formats") or []
    if not formats and info.get("url"):
        formats = [dict(info, format_id=info.get("format_id", "0"))]
    plan = select_formats(formats, mode, quality)

    def size(f: dict | None) -> float:
        return float((f or {}).get("filesize") or (f or {}).get("filesize_approx") or 0)

    v, a = plan["video"], plan["audio"]
    estimates = {}
    if v:
        estimates["v"] = size(v)
    if a:
        estimates["a"] = size(a)
    if not v and not a:
        raise me.DownloadFailure("No downloadable (non-DRM) formats were found.")
    if not v:
        estimates = {"m": size(a)}
    elif not a:
        estimates = {"m": size(v)}
    tracker = _Tracker(callback, estimates)
    title = str(info.get("title") or "media")
    result: dict[str, Any] = {"display_name": title, "duration_seconds": float(info.get("duration") or 0)}

    if v and a:
        # Video and audio are fetched in parallel; the merge happens afterwards in FFmpeg.
        with ThreadPoolExecutor(max_workers=2) as pool:
            fv = pool.submit(_download_format, url, v, directory, "v", tracker, callback, factory, profile)
            fa = pool.submit(_download_format, url, a, directory, "a", tracker, callback, factory, profile)
            errors = []
            paths = []
            for future in (fv, fa):
                try:
                    paths.append(future.result())
                except BaseException as error:  # noqa: BLE001
                    tracker.abort.set()
                    errors.append(error)
            if errors:
                real = [e for e in errors if not isinstance(e, me.DownloadCancelled)]
                raise (real or errors)[0]
        vp, ap = paths
        result.update({"kind": "merge", "video_path": str(vp), "audio_path": str(ap), "container": "mp4"})
    else:
        chosen = v or a
        tracker.total.setdefault("m", size(chosen))
        path = _download_format(url, chosen, directory, "m", tracker, callback, factory, profile)
        result.update({"kind": "single", "path": str(path), "ext": path.suffix.lstrip(".").lower(),
                       "has_video": bool(v), "container": "mp4"})
    me._emit_progress(callback, 100, "download_finished", "")
    return json.dumps(result, ensure_ascii=False)
