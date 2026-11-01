import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'app' / 'src' / 'main' / 'python'))
import soundwave_backend as sw


class FakeYDL:
    def __init__(self, options): self.opts = options
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def extract_info(self, url, download):
        if 'playlist' in url: return {'_type': 'playlist', 'entries': []}
        info = {'title': 'Artist - Song', 'artist': 'Artist', 'id': '123',
                'thumbnail': 'https://example.net/pic.jpg'}
        if download:
            outpath = self.opts['outtmpl'].replace('%(id)s', '123').replace('%(ext)s', 'm4a')
            Path(outpath).write_bytes(b'fake-audio')
            for hook in self.opts['progress_hooks']:
                hook({'status': 'downloading', 'downloaded_bytes': 50, 'total_bytes': 100})
                hook({'status': 'finished'})
        return info


class TestBackend(unittest.TestCase):
    def setUp(self):
        self.previous = sys.modules.get('yt_dlp')
        sys.modules['yt_dlp'] = types.SimpleNamespace(YoutubeDL=FakeYDL)

    def tearDown(self):
        if self.previous is None: sys.modules.pop('yt_dlp', None)
        else: sys.modules['yt_dlp'] = self.previous

    def test_urls(self):
        for url in ['https://soundcloud.com/artist/song', 'https://on.soundcloud.com/abc',
                    'https://m.soundcloud.com/test/123']:
            self.assertEqual(sw.validate_url(url), url)
        for url in ['', 'https://evil.com/soundcloud.com/x', 'http://soundcloud.com/a/b',
                    'https://soundcloud.com.evil.net/a', 'https://soundcloud.com/']:
            with self.assertRaises(ValueError): sw.validate_url(url)

    def test_sanitization(self):
        self.assertEqual(sw.clean_name(' <Hi>:/\\*?" '), 'Hi')

    def test_metadata(self):
        info = json.loads(sw.inspect('https://soundcloud.com/artist/song'))
        self.assertEqual(info['title'], 'Song')
        self.assertEqual(info['artist'], 'Artist')
        self.assertTrue(info['thumbnail'].startswith('https'))

    def test_playlist_rejected(self):
        with self.assertRaises(ValueError): sw.inspect('https://soundcloud.com/a/playlist')

    def test_download(self):
        with tempfile.TemporaryDirectory() as tmp:
            progress = os.path.join(tmp, 'progress.json')
            result = json.loads(sw.download('https://soundcloud.com/artist/song', tmp, progress))
            self.assertEqual(result['filename'], 'Artist - Song.m4a')
            self.assertEqual(Path(result['path']).read_bytes(), b'fake-audio')
            self.assertEqual(json.loads(Path(progress).read_text())['percent'], 100)

if __name__ == '__main__': unittest.main()
