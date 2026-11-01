"""Download orchestrator for the Android/Chaquopy runtime.

Direct media/HLS/DASH URLs are handled here with the standard library; page URLs
(YouTube, TikTok, ...) are delegated to ytdlp_engine (official yt-dlp extractors).


This module downloads a single HTTPS media file from a URL the user is
already authorized to access. It does not extract media from HTML pages,
forge platform attestations, rotate identities/proxies, or bypass access
controls. Platform-specific share/watch pages must be handled by the
platform's supported export/download flow.

The transfer is streamed to a sibling .part file, validated, SHA-256 hashed,
and atomically committed with os.replace(). The module only uses Python's
standard library, so no third-party Python wheels are needed in the APK.
"""

from __future__ import annotations

import hashlib
import http.client
import ipaddress
import json
import os
import random
import re
import socket
import time
import uuid
from email.message import Message
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urljoin, urlsplit
from xml.etree import ElementTree
from urllib.request import HTTPRedirectHandler, Request, build_opener


VIDEO_QUALITIES = {"best", "4320p", "2160p", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p"}
AUDIO_BITRATES = {"best", "320k", "256k", "192k", "128k"}
DIRECT_EXTENSIONS = {
    ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".mp3", ".m4a", ".aac", ".ogg", ".opus",
    ".wav", ".flac", ".m3u8", ".mpd",
}
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}
MAX_ATTEMPTS = 4
MAX_REDIRECTS = 5
MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024 * 1024
CHUNK_SIZE = 1024 * 1024
PROGRESS_INTERVAL = 0.3
CONNECT_TIMEOUT_SECONDS = 30
MAX_RETRY_AFTER_SECONDS = 120
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
MAX_HLS_CHILD_PLAYLISTS = 12


class DownloadCancelled(Exception):
    """The user or WorkManager cancelled the transfer."""


class DownloadFailure(RuntimeError):
    """Base class for user-safe, actionable download errors."""


class AuthenticationRequired(DownloadFailure):
    """The provider requires a valid authorized session."""


class AccessDenied(DownloadFailure):
    """The media server explicitly denied access."""


class RateLimited(DownloadFailure):
    """The provider rate-limited the client and the retry window is too long."""


class IntegrityFailure(DownloadFailure):
    """The received media failed structural or cryptographic validation."""


class IncompleteTransfer(IntegrityFailure):
    """The connection ended before the declared number of bytes arrived."""


class _RedirectPolicy(HTTPRedirectHandler):
    """Allow HTTPS redirects only and validate each new destination."""

    max_redirections = MAX_REDIRECTS
    max_repeats = MAX_REDIRECTS

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl)
        _validate_resolved_host(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def validate_url(url: str) -> str:
    value = (url or "").strip()
    if not value or any(ch in value for ch in ("\r", "\n", "\x00", "\\")):
        raise ValueError("Enter a valid HTTPS direct media URL.")

    parsed = urlsplit(value)
    if parsed.scheme.lower() != "https" or not parsed.hostname:
        raise ValueError("Only HTTPS direct media URLs are supported.")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("URLs containing embedded usernames or passwords are not supported.")
    if parsed.port not in (None, 443):
        raise ValueError("Only standard HTTPS port 443 is supported.")

    host = parsed.hostname.rstrip(".").lower()
    if host in {"localhost", "localhost.localdomain"} or host.endswith((".localhost", ".local", ".internal")):
        raise ValueError("Private or local network addresses are not allowed.")

    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        address = None
    if address is not None and not address.is_global:
        raise ValueError("Private or non-public IP addresses are not allowed.")

    return value


def _validate_resolved_host(url: str) -> None:
    """Reject hostnames which resolve exclusively or partly to non-public IPs."""
    host = urlsplit(url).hostname
    if not host:
        raise ValueError("The media URL has no host.")
    try:
        records = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except OSError as exc:
        raise DownloadFailure("The media host could not be resolved. Check your network.") from exc

    addresses = {record[4][0].split("%", 1)[0] for record in records}
    if not addresses:
        raise DownloadFailure("The media host did not resolve to an IP address.")
    for raw_address in addresses:
        try:
            address = ipaddress.ip_address(raw_address)
        except ValueError as exc:
            raise DownloadFailure("The media host returned an invalid IP address.") from exc
        if not address.is_global:
            raise DownloadFailure("The media host resolved to a private or non-public IP address.")


def _is_cancelled(callback: Any) -> bool:
    if callback is None:
        return False
    check = getattr(callback, "isCancelled", None)
    return bool(check()) if callable(check) else False


def _emit_progress(callback: Any, percent: float, stage: str, detail: str) -> None:
    if callback is None:
        return
    notify = getattr(callback, "onProgress", None)
    if callable(notify):
        value = -1.0 if percent < 0 else min(100.0, float(percent))
        notify(value, stage, detail)


def progress_detail(done: float, total: float, speed: float = 0.0, eta: float = -1.0) -> str:
    """Compact transfer statistics for the UI: bytes done / total, bytes per second, ETA seconds."""
    return json.dumps({"d": int(done or 0), "t": int(total or 0), "s": int(speed or 0),
                       "e": int(eta) if eta is not None and eta >= 0 else -1})


def _retry_after_seconds(headers: Message | Any) -> float | None:
    value = headers.get("Retry-After") if headers is not None else None
    if not value:
        return None
    try:
        return max(0.0, float(value.strip()))
    except (AttributeError, TypeError, ValueError):
        pass
    try:
        from email.utils import parsedate_to_datetime
        when = parsedate_to_datetime(value)
        if when.tzinfo is None:
            when = when.replace(tzinfo=timezone.utc)
        return max(0.0, (when - datetime.now(timezone.utc)).total_seconds())
    except (TypeError, ValueError, OverflowError):
        return None


def _backoff_seconds(attempt: int, retry_after: float | None = None) -> float:
    if retry_after is not None:
        return retry_after
    ceiling = min(2 ** max(0, attempt), 30)
    return random.uniform(ceiling * 0.75, ceiling * 1.25)


def _content_type(headers: Message | Any) -> str:
    raw = headers.get("Content-Type", "application/octet-stream") if headers is not None else "application/octet-stream"
    return raw.split(";", 1)[0].strip().lower() or "application/octet-stream"


def _safe_extension(url: str, content_type: str, mode: str) -> str:
    mapping = {
        "audio/mpeg": ".mp3",
        "audio/mp4": ".m4a",
        "audio/aac": ".aac",
        "audio/wav": ".wav",
        "audio/x-wav": ".wav",
        "audio/ogg": ".ogg",
        "audio/opus": ".opus",
        "video/mp4": ".mp4",
        "video/webm": ".webm",
        "video/quicktime": ".mov",
        "video/x-matroska": ".mkv",
    }
    if content_type in mapping:
        return mapping[content_type]

    suffix = Path(urlsplit(url).path).suffix.lower()
    if re.fullmatch(r"\.[a-z0-9]{1,8}", suffix) and suffix not in {".html", ".htm", ".php", ".asp", ".aspx"}:
        return suffix
    return ".mp4" if mode == "MP4" else ".bin"


def _check_media_response(content_type: str, mode: str) -> None:
    if content_type in {"text/html", "application/xhtml+xml", "application/json", "text/plain"}:
        raise DownloadFailure(
            "The URL returned a web page or API response, not a direct media file. "
            "Use the source provider's authorized media/export link."
        )

    if not (
        content_type.startswith("audio/")
        or content_type.startswith("video/")
        or content_type in {"application/octet-stream", "binary/octet-stream"}
    ):
        raise DownloadFailure(f"The server returned unsupported media type: {content_type}.")


def _validate_media_file(path: Path) -> None:
    """Cheap sanity check; FFprobe in the app performs the real container validation."""
    try:
        with path.open("rb") as source:
            header = source.read(64).lstrip()
    except OSError as exc:
        raise IntegrityFailure("The downloaded file could not be read for validation.") from exc
    if len(header) < 12 or header[:1] in (b"<", b"{", b"["):
        raise IntegrityFailure("The response is not a media file. Use a direct media URL.")


def _make_opener():
    return build_opener(_RedirectPolicy())


def _adaptive_kind(url: str) -> str | None:
    """Identify explicitly supplied HLS/DASH manifest URLs by path suffix."""
    suffix = Path(urlsplit(url).path.lower()).suffix
    if suffix == ".m3u8":
        return "hls"
    if suffix == ".mpd":
        return "dash"
    return None


def _read_manifest(url: str, opener: Any) -> str:
    """Fetch a small public HTTPS manifest; no cookies or challenge logic are used."""
    request = Request(
        url,
        headers={"Accept": "application/vnd.apple.mpegurl, application/dash+xml, application/xml, text/plain;q=0.8, */*;q=0.1",
                 "User-Agent": "UniversalMediaDownloader/2.1 (authorized-media-client)"},
        method="GET",
    )
    try:
        response = opener.open(request, timeout=CONNECT_TIMEOUT_SECONDS)
    except HTTPError as error:
        try:
            if error.code in {401, 403}:
                raise _status_error(error) from error
            raise DownloadFailure(f"The manifest server returned HTTP {error.code}.") from error
        finally:
            error.close()
    except (URLError, TimeoutError, socket.timeout, ConnectionError) as error:
        raise DownloadFailure("The media manifest could not be reached. Check the URL and network.") from error

    try:
        status = getattr(response, "status", None) or response.getcode()
        if status != 200:
            raise DownloadFailure(f"The manifest server returned HTTP {status}.")
        raw = response.read(MAX_MANIFEST_BYTES + 1)
        if len(raw) > MAX_MANIFEST_BYTES:
            raise DownloadFailure("The media manifest is larger than the 2 MiB safety limit.")
        if not raw:
            raise DownloadFailure("The media manifest is empty.")
        return raw.decode("utf-8-sig", errors="replace")
    finally:
        response.close()


def _hls_key_is_protected(text: str) -> bool:
    for line in text.splitlines():
        value = line.strip()
        if value.upper().startswith(("#EXT-X-KEY:", "#EXT-X-SESSION-KEY:")):
            method = re.search(r"METHOD\s*=\s*([^,\s]+)", value, flags=re.IGNORECASE)
            if not method or method.group(1).upper() != "NONE":
                return True
    return False


def _validate_manifest_reference(base_url: str, reference: str, checked_hosts: set[str]) -> str:
    target = validate_url(urljoin(base_url, reference.strip()))
    host = (urlsplit(target).hostname or "").lower()
    if host not in checked_hosts:
        _validate_resolved_host(target)
        checked_hosts.add(host)
    return target


def _hls_child_playlists(text: str, base_url: str) -> list[str]:
    lines = [line.strip() for line in text.splitlines()]
    children: list[str] = []
    for index, line in enumerate(lines):
        if line.upper().startswith(("#EXT-X-MEDIA:", "#EXT-X-I-FRAME-STREAM-INF:")):
            match = re.search(r'URI\s*=\s*"([^"]+)"', line, flags=re.IGNORECASE)
            if match:
                children.append(urljoin(base_url, match.group(1)))
        elif line.upper().startswith("#EXT-X-STREAM-INF:"):
            for candidate in lines[index + 1:]:
                if candidate and not candidate.startswith("#"):
                    children.append(urljoin(base_url, candidate))
                    break
    return children


def _hls_duration_seconds(text: str) -> float:
    durations = re.findall(r"^\s*#EXTINF:([0-9]+(?:\.[0-9]+)?)", text, flags=re.IGNORECASE | re.MULTILINE)
    try:
        return sum(float(value) for value in durations)
    except (TypeError, ValueError, OverflowError):
        return 0.0


def _dash_duration_seconds(text: str) -> float:
    match = re.search(r'\bmediaPresentationDuration\s*=\s*["\']P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?["\']', text, flags=re.IGNORECASE)
    if not match:
        return 0.0
    days, hours, minutes, seconds = match.groups()
    return (int(days or 0) * 86400.0 + int(hours or 0) * 3600.0 +
            int(minutes or 0) * 60.0 + float(seconds or 0.0))


def _validate_hls_manifest(url: str, opener: Any) -> float:
    pending = [url]
    checked: set[str] = set()
    checked_hosts: set[str] = set()
    durations: list[float] = []
    while pending:
        current = pending.pop(0)
        if current in checked:
            continue
        if len(checked) >= MAX_HLS_CHILD_PLAYLISTS:
            raise DownloadFailure("The HLS playlist contains too many nested variant playlists.")
        safe_url = validate_url(current)
        _validate_resolved_host(safe_url)
        text = _read_manifest(safe_url, opener)
        if not text.lstrip().startswith("#EXTM3U"):
            raise DownloadFailure("The supplied .m3u8 URL did not return a valid HLS playlist.")
        if _hls_key_is_protected(text):
            raise DownloadFailure("Encrypted HLS playlists are not supported. Use a clear, non-DRM stream.")
        checked.add(current)
        children = _hls_child_playlists(text, current)
        # Resolve and validate every concrete URI before FFmpeg follows the playlist.
        for line in text.splitlines():
            value = line.strip()
            if value and not value.startswith("#"):
                _validate_manifest_reference(current, value, checked_hosts)
            for match in re.finditer(r'URI\s*=\s*"([^"]+)"', value, flags=re.IGNORECASE):
                _validate_manifest_reference(current, match.group(1), checked_hosts)
        if children:
            pending.extend(child for child in children if child not in checked)
        elif "#EXT-X-ENDLIST" not in text.upper():
            raise DownloadFailure("Live HLS playlists are not supported; provide a finite VOD playlist.")
        else:
            durations.append(_hls_duration_seconds(text))
    return max(durations, default=0.0)


def _validate_dash_manifest(url: str, opener: Any) -> float:
    text = _read_manifest(url, opener)
    if not re.search(r"<\s*(?:[\w.-]+:)?MPD(?:\s|>)", text, flags=re.IGNORECASE):
        raise DownloadFailure("The supplied .mpd URL did not return a valid DASH MPD manifest.")
    if re.search(r"<\s*(?:[\w.-]+:)?ContentProtection\b|cenc:default_KID|<\s*pssh\b", text, flags=re.IGNORECASE):
        raise DownloadFailure("DRM-protected DASH manifests are not supported. Use a clear, non-DRM stream.")
    if re.search(r'\btype\s*=\s*["\']dynamic["\']', text, flags=re.IGNORECASE):
        raise DownloadFailure("Live/dynamic DASH manifests are not supported; provide a finite VOD manifest.")
    try:
        document = ElementTree.fromstring(text)
    except ElementTree.ParseError as error:
        raise DownloadFailure("The supplied .mpd URL returned malformed XML.") from error

    checked_hosts = {urlsplit(url).hostname.lower()}
    _validate_resolved_host(url)
    url_attributes = {"media", "sourceURL", "initialization", "index", "href"}

    def visit(element: ElementTree.Element, inherited_base: str) -> None:
        current_base = inherited_base
        for child in list(element):
            tag = child.tag.rsplit("}", 1)[-1].lower()
            if tag == "baseurl" and child.text and child.text.strip():
                current_base = _validate_manifest_reference(current_base, child.text, checked_hosts)
            for attribute, value in child.attrib.items():
                if attribute.rsplit("}", 1)[-1] in url_attributes and value and "$" not in value:
                    _validate_manifest_reference(current_base, value, checked_hosts)
            visit(child, current_base)

    visit(document, url)
    return _dash_duration_seconds(text)


def _part_paths(directory: Path, url: str) -> tuple[Path, Path]:
    """Stable per-URL partial file (+ validator sidecar) so a transfer can resume."""
    key = hashlib.sha256(url.encode("utf-8")).hexdigest()[:24]
    return directory / f"direct-{key}.part", directory / f"direct-{key}.meta"


def _read_meta(meta: Path) -> dict:
    try:
        data = json.loads(meta.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _discard_partial(temporary: Path, meta: Path) -> None:
    temporary.unlink(missing_ok=True)
    meta.unlink(missing_ok=True)


def _parse_content_range(value: str | None) -> tuple[int, int | None] | None:
    match = re.fullmatch(r"\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*", value or "")
    if not match:
        return None
    total = None if match.group(3) == "*" else int(match.group(3))
    return int(match.group(1)), total


def _download_once(url: str, mode: str, temporary: Path, callback: Any, opener: Any,
                   meta: Path | None = None, _allow_range: bool = True) -> dict:
    """Stream ``url`` into ``temporary``; resume with HTTP Range when a valid partial exists.

    The partial file is kept on retryable failures. It is discarded when the server
    cannot prove (ETag / Last-Modified via If-Range) that it is the same resource.
    """
    meta = meta or temporary.with_suffix(".meta")
    headers_out = {
        "Accept": "video/mp4,video/*,audio/*,application/octet-stream;q=0.9,*/*;q=0.1",
        "User-Agent": "UniversalMediaDownloader/2.0 (authorized-media-client)",
        "Accept-Encoding": "identity",
    }
    offset = temporary.stat().st_size if (_allow_range and temporary.is_file()) else 0
    saved = _read_meta(meta) if offset else {}
    validator = saved.get("etag") or saved.get("last_modified")
    if offset and not validator:
        _discard_partial(temporary, meta)  # cannot prove identity of the partial -> restart safely
        offset = 0
    if offset:
        headers_out["Range"] = f"bytes={offset}-"
        headers_out["If-Range"] = validator
    request = Request(url, headers=headers_out, method="GET")

    try:
        response = opener.open(request, timeout=CONNECT_TIMEOUT_SECONDS)
    except HTTPError as error:
        if int(error.code) == 416 and offset:
            error.close()
            _discard_partial(temporary, meta)
            return _download_once(url, mode, temporary, callback, opener, meta, _allow_range=False)
        raise
    try:
        status = getattr(response, "status", None) or response.getcode()
        headers = getattr(response, "headers", None)
        if status not in (200, 206):
            raise DownloadFailure(f"Media server returned HTTP {status}.")
        content_type = _content_type(headers)
        _check_media_response(content_type, mode)

        if status == 206:
            parsed = _parse_content_range(headers.get("Content-Range") if headers is not None else None)
            if not offset or parsed is None or parsed[0] != offset:
                _discard_partial(temporary, meta)
                raise IncompleteTransfer("The server returned an unexpected byte range; restarting.")
            total_expected = parsed[1]
        else:
            if offset:  # server ignored Range / resource changed: start from zero
                offset = 0
            raw_length = headers.get("Content-Length") if headers is not None else None
            total_expected = None
            if raw_length:
                try:
                    total_expected = int(raw_length)
                except (TypeError, ValueError):
                    raise IntegrityFailure("The server returned an invalid Content-Length header.")
                if total_expected <= 0:
                    raise IntegrityFailure("The server reported an empty media file.")
        if total_expected is not None and total_expected > MAX_DOWNLOAD_BYTES:
            raise DownloadFailure("The media file exceeds the 20 GiB application limit.")

        if headers is not None and offset == 0:
            tag = headers.get("ETag") or ""
            modified = headers.get("Last-Modified") or ""
            if tag.startswith("W/"):  # weak validators are not valid for If-Range
                tag = ""
            try:
                meta.write_text(json.dumps({"etag": tag, "last_modified": modified}), encoding="utf-8")
            except OSError:
                pass

        total = offset
        display_name = unquote(Path(urlsplit(url).path).name) or "media"
        started = last_emit = time.monotonic()
        last_bytes = total
        speed = 0.0
        with temporary.open("ab" if offset else "wb") as output:
            while True:
                if _is_cancelled(callback):
                    raise DownloadCancelled("Download cancelled by user.")
                chunk = response.read(CHUNK_SIZE)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_DOWNLOAD_BYTES:
                    _discard_partial(temporary, meta)
                    raise DownloadFailure("The media file exceeds the 20 GiB application limit.")
                output.write(chunk)
                now = time.monotonic()
                if now - last_emit >= PROGRESS_INTERVAL:
                    instant = (total - last_bytes) / max(now - last_emit, 1e-6)
                    speed = instant if speed == 0.0 else speed * 0.7 + instant * 0.3
                    last_emit, last_bytes = now, total
                    percent = min(99.0, total * 100.0 / total_expected) if total_expected else -1.0
                    eta = (total_expected - total) / speed if (total_expected and speed > 0) else -1.0
                    _emit_progress(callback, percent, "progress",
                                   progress_detail(total, total_expected or 0, speed, eta))
            output.flush()
            os.fsync(output.fileno())

        if total <= 0:
            _discard_partial(temporary, meta)
            raise IntegrityFailure("The server returned an empty media file.")
        if total_expected is not None and total != total_expected:
            # Keep the partial file: the next attempt resumes from `total`.
            raise IncompleteTransfer(f"Incomplete transfer: received {total} of {total_expected} bytes.")

        try:
            _validate_media_file(temporary)
        except IntegrityFailure:
            _discard_partial(temporary, meta)
            raise

        _emit_progress(callback, 100.0, "download_finished", display_name)
        return {
            "bytes": total,
            "content_type": content_type,
            "display_name": display_name,
        }
    finally:
        response.close()


def _status_error(error: HTTPError) -> DownloadFailure:
    status = int(error.code)
    if status == 401:
        return AuthenticationRequired(
            "The media provider returned HTTP 401. Sign in using its supported flow "
            "or obtain a fresh authorized media URL."
        )
    if status == 403:
        return AccessDenied(
            "The media provider returned HTTP 403. Access was explicitly denied. "
            "Check the asset permission, account entitlement, region policy, or signed URL. "
            "This app does not forge attestation, rotate identities, or bypass access controls."
        )
    if status == 404:
        return DownloadFailure("The media file was not found (HTTP 404).")
    if status == 416:
        return DownloadFailure("The media server rejected the requested byte range (HTTP 416).")
    return DownloadFailure(f"The media server returned HTTP {status}.")


def _is_direct_media_url(url: str) -> bool:
    return Path(unquote(urlsplit(url).path)).suffix.lower() in DIRECT_EXTENSIONS


def analyze_url(url: str) -> str:
    """Return JSON describing what can be downloaded from ``url``."""
    url = validate_url(url)
    _validate_resolved_host(url)
    if _is_direct_media_url(url):
        name = unquote(Path(urlsplit(url).path).stem) or "media"
        audio_ext = Path(urlsplit(url).path).suffix.lower() in {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac"}
        return json.dumps({
            "title": name, "duration": 0, "thumbnail": "", "extractor": "direct",
            "has_video": not audio_ext, "has_audio": True, "heights": [], "max_height": 0,
        }, ensure_ascii=False)
    import ytdlp_engine
    return json.dumps(ytdlp_engine.analyze(url), ensure_ascii=False)


def download_media(
    url: str,
    mode: str,
    quality: str,
    work_dir: str,
    callback: Any,
    opener: Any = None,
) -> str:
    """Download a direct HTTPS file or pass through a clear HLS/DASH VOD manifest.

    Args:
        url: Direct media URL or explicit .m3u8/.mpd manifest URL; not a watch/share webpage.
        mode: ``MP4`` to retain a validated MP4, or ``MP3`` to download source
              audio/video for native FFmpeg MP3 conversion in MediaWorker.
        quality: Existing UI quality value. For MP3 it is the output bitrate.
        work_dir: Worker-owned private cache directory.
        callback: PythonProgressCallback bridge from Kotlin.
        opener: Optional test transport. The Android app does not pass this.
    """
    url = validate_url(url)
    mode = (mode or "").strip().upper()
    quality = (quality or "").strip().lower()

    if mode not in {"MP4", "MP3"}:
        raise ValueError("Unsupported output format.")
    if mode == "MP4" and quality not in VIDEO_QUALITIES:
        raise ValueError("Unsupported video quality selection.")
    if mode == "MP3" and quality not in AUDIO_BITRATES:
        raise ValueError("Unsupported MP3 bitrate selection.")

    directory = Path(work_dir).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    if not directory.is_dir():
        raise DownloadFailure("The private download directory is unavailable.")

    _validate_resolved_host(url)
    if opener is None and not _is_direct_media_url(url):
        # Production path (the app never injects an opener); tests inject one to force direct mode.
        import ytdlp_engine  # page URL: use yt-dlp's official extractors
        return ytdlp_engine.download(url, mode, quality, str(directory), callback)
    transport = opener or _make_opener()

    adaptive_kind = _adaptive_kind(url)
    if adaptive_kind == "hls":
        duration_seconds = _validate_hls_manifest(url, transport)
        display_name = unquote(Path(urlsplit(url).path).stem) or "video"
        return json.dumps({
            "kind": "adaptive", "manifest": "hls", "url": url,
            "duration_seconds": duration_seconds, "display_name": display_name,
        }, ensure_ascii=False)
    if adaptive_kind == "dash":
        duration_seconds = _validate_dash_manifest(url, transport)
        display_name = unquote(Path(urlsplit(url).path).stem) or "video"
        return json.dumps({
            "kind": "adaptive", "manifest": "dash", "url": url,
            "duration_seconds": duration_seconds, "display_name": display_name,
        }, ensure_ascii=False)

    last_error: BaseException | None = None
    part_path, meta_path = _part_paths(directory, url)

    for attempt in range(MAX_ATTEMPTS):
        if _is_cancelled(callback):
            raise DownloadCancelled("Download cancelled by user.")  # partial kept so it can resume

        try:
            result = _download_once(url, mode, part_path, callback, transport, meta_path)
            extension = _safe_extension(url, result["content_type"], mode)
            final_path = directory / f"media-{uuid.uuid4().hex}{extension}"
            os.replace(part_path, final_path)
            meta_path.unlink(missing_ok=True)
            result.update({"kind": "single", "path": str(final_path)})
            return json.dumps(result, ensure_ascii=False)

        except HTTPError as error:
            last_error = error
            try:
                status = int(error.code)
                if status not in RETRYABLE_STATUS:
                    _discard_partial(part_path, meta_path)
                    raise _status_error(error) from error

                retry_after = _retry_after_seconds(error.headers)
                if status == 429 and retry_after is None:
                    retry_after = _backoff_seconds(attempt + 1)
                if retry_after is not None and retry_after > MAX_RETRY_AFTER_SECONDS:
                    raise RateLimited(
                        f"The provider requested a retry after {int(retry_after)} seconds. "
                        "Wait for its Retry-After window and start a new download."
                    ) from error
                if attempt + 1 >= MAX_ATTEMPTS:
                    raise RateLimited("The provider kept rate-limiting or rejecting temporary requests; retry later.") from error
                time.sleep(_backoff_seconds(attempt + 1, retry_after))
            finally:
                error.close()

        except (
            IncompleteTransfer,
            http.client.IncompleteRead,
            http.client.RemoteDisconnected,
            http.client.BadStatusLine,
            URLError,
            TimeoutError,
            socket.timeout,
            ConnectionError,
        ) as error:
            last_error = error
            if attempt + 1 >= MAX_ATTEMPTS:
                # Partial stays on disk so a later retry (WorkManager) can resume.
                raise IncompleteTransfer(
                    "Network transfer failed or ended early after the allowed retry attempts. "
                    "Check connectivity and try later."
                ) from error
            time.sleep(_backoff_seconds(attempt + 1))

        except DownloadCancelled:
            raise

        except IntegrityFailure:
            _discard_partial(part_path, meta_path)
            raise

    raise DownloadFailure("The download could not be completed.") from last_error
