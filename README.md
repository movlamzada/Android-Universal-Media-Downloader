<div align="center">

# Universal Media Downloader

**Android · Kotlin + Jetpack Compose · yt-dlp · FFmpeg**

[![Download APK](https://img.shields.io/badge/⬇_Download-UniversalMediaDownloader.apk-2457D6?style=for-the-badge)](../../releases/latest/download/UniversalMediaDownloader.apk)

[🇦🇿 Azərbaycanca](#-azərbaycanca) · [🇬🇧 English](#-english) · [All releases](../../releases)

</div>

---

## 🇦🇿 Azərbaycanca

Video və musiqi linkini yapışdırın, keyfiyyəti seçin, **MP4** və ya **MP3** olaraq telefona yükləyin.

### Quraşdırma
1. **[UniversalMediaDownloader.apk](../../releases/latest/download/UniversalMediaDownloader.apk)** faylını endirin (bütün versiyalar: [Releases](../../releases)).
2. Faylı açın. Telefon xəbərdarlıq etsə, **"Bu mənbədən quraşdırmağa icazə ver"** seçin.
3. **Quraşdır** düyməsinə basın.

Tələb: Android 8.0+ (64-bit cihaz).

### İstifadə
1. **Yapışdır** düyməsinə basın (link avtomatik əlavə olunmur) → **Analiz et**.
2. Formatı seçin: **Video (MP4)** və ya **Audio (MP3)**, sonra keyfiyyəti.
3. Çıxış qovluğunu seçin → **Endirməyə başla**.
4. Gedişat (faiz, ölçü, sürət, qalan vaxt) "Əməliyyatın vəziyyəti" bölməsində görünür.
5. Hazır fayllar **Tarixçə** bölməsindədir: aç, paylaş, yaddaşdan sil. **Tarixçəni təmizlə** yalnız siyahını silir, fayllar qalır.

**Çevirici** bölməsi telefondakı videoları MP3-ə çevirir.

### Qeydlər
- Fayllar həmişə `.mp4` və ya `.mp3` olaraq saxlanılır.
- Yarımçıq qalan yükləmə şəbəkə bərpa olanda davam edir.
- DRM-li, canlı yayım və giriş tələb edən məzmun dəstəklənmir.
- Yalnız yükləmə hüququnuz olan məzmunu endirin; müəllif hüquqlarına və sayt qaydalarına riayət etmək istifadəçinin məsuliyyətidir.

### Özünüz build etmək
Tələb: JDK 17+, Android SDK 36, Python 3.10–3.15, internet.
```bash
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
```
Nəticə: `UniversalMediaDownloader.apk` (layihənin kök qovluğunda).

---

## 🇬🇧 English

Paste a video or music link, pick the quality, and save it to your phone as **MP4** or **MP3**.

### Install
1. Download **[UniversalMediaDownloader.apk](../../releases/latest/download/UniversalMediaDownloader.apk)** (all versions: [Releases](../../releases)).
2. Open the file. If Android warns you, allow **"Install from this source"**.
3. Tap **Install**.

Requires Android 8.0+ (64-bit device).

### Usage
1. Tap **Paste** (the link is never pasted automatically) → **Analyze**.
2. Choose **Video (MP4)** or **Audio (MP3)**, then the quality.
3. Pick an output folder → **Start download**.
4. Progress (percent, size, speed, time left) is shown in the **Task status** card.
5. Finished files are listed in **History**: open, share, or delete from storage. **Clear history** removes only the list — your files stay.

The **Converter** tab turns videos already on your phone into MP3.

### Notes
- Files are always saved as `.mp4` or `.mp3`.
- Interrupted downloads resume automatically when the network returns.
- DRM-protected, live, and sign-in-only content is not supported.
- Download only content you have the right to. You are responsible for copyright and site terms.

### Build from source
Requires JDK 17+, Android SDK 36, Python 3.10–3.15, internet.
```bash
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
```
Output: `UniversalMediaDownloader.apk` (project root).

---

Third-party components: yt-dlp, FFmpegKit, Chaquopy, AndroidX — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
