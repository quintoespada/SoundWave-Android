"""SoundWave metadata and audio download backend. Called from Chaquopy on a worker thread."""
import json
import os
import re
import tempfile
from pathlib import Path
from urllib.parse import urlsplit


def validate_url(url):
    url = (url or '').strip()
    try:
        parts = urlsplit(url)
        hostname = (parts.hostname or '').lower()
        valid = hostname == 'soundcloud.com' or hostname.endswith('.soundcloud.com')
        if parts.scheme != 'https' or not valid or not parts.path.strip('/'):
            raise ValueError
    except ValueError:
        raise ValueError('Нужна HTTPS-ссылка на трек SoundCloud.') from None
    return url


def clean_name(value):
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '', str(value or ''))
    value = re.sub(r'\s+', ' ', value).strip(' .')
    return value[:90] or 'Track'


def title_artist(info):
    artist = str(info.get('artist') or info.get('uploader') or info.get('creator') or 'Unknown Artist')
    title = str(info.get('track') or info.get('title') or 'Track')
    # Some extractors include the artist in title; don't repeat it.
    if title.lower().startswith(artist.lower() + ' - '):
        title = title[len(artist) + 3:]
    return title, artist


def assert_single(info):
    if not isinstance(info, dict) or info.get('entries') is not None or info.get('_type') in ('playlist', 'multi_video'):
        raise ValueError('Пока поддерживаются ссылки на отдельные треки, не плейлисты.')


def metadata_dict(info):
    assert_single(info)
    title, artist = title_artist(info)
    return {'title': title, 'artist': artist,
            'thumbnail': info.get('thumbnail') or '', 'duration': info.get('duration') or 0}


def inspect(url):
    import yt_dlp
    url = validate_url(url)
    with yt_dlp.YoutubeDL({'quiet': True, 'no_warnings': True, 'noplaylist': True,
                           'skip_download': True}) as ydl:
        info = ydl.extract_info(url, download=False)
    return json.dumps(metadata_dict(info), ensure_ascii=False)


def write_progress(progress_path, pct):
    if not progress_path:
        return
    tmp = progress_path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump({'percent': max(0, min(100, int(pct)))}, f)
    os.replace(tmp, progress_path)


def download(url, cache_dir, progress_path):
    import yt_dlp
    url = validate_url(url)
    cache = Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='sw_', dir=str(cache)))
    write_progress(progress_path, 0)

    def hook(data):
        if data.get('status') == 'finished':
            write_progress(progress_path, 100)
        elif data.get('status') == 'downloading':
            total = data.get('total_bytes') or data.get('total_bytes_estimate') or 0
            if total:
                write_progress(progress_path, 100 * (data.get('downloaded_bytes') or 0) / total)

    options = {'format': 'bestaudio/best', 'quiet': True, 'no_warnings': True,
               'noplaylist': True, 'progress_hooks': [hook],
               'outtmpl': str(output / '%(id)s.%(ext)s')}
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
    assert_single(info)
    files = [p for p in output.iterdir() if p.is_file() and not p.name.endswith(('.part', '.ytdl', '.tmp'))]
    if not files:
        raise RuntimeError('Не удалось найти скачанный файл.')
    audio = max(files, key=lambda p: p.stat().st_size)
    extension = audio.suffix.lower() or '.audio'
    title, artist = title_artist(info)
    nice_name = clean_name(artist) + ' - ' + clean_name(title) + extension
    write_progress(progress_path, 100)
    return json.dumps({'path': str(audio), 'filename': nice_name}, ensure_ascii=False)
