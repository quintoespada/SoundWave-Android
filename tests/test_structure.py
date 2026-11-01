from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]

class TestStructure(unittest.TestCase):
    def test_xml(self):
        for file in (ROOT / 'app/src/main').rglob('*.xml'):
            ET.parse(file)

    def test_required_ids(self):
        activity = (ROOT / 'app/src/main/java/com/soundwave/mobile/MainActivity.java').read_text()
        layout = (ROOT / 'app/src/main/res/layout/activity_main.xml').read_text()
        for name in ['urlInput','pasteButton','searchButton','downloadButton','trackCard',
                     'trackTitle','trackArtist','statusLabel','coverPlaceholder','coverImage','progress']:
            self.assertIn('android:id="@+id/' + name + '"', layout)
            self.assertIn('R.id.' + name, activity)

    def test_manifest(self):
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android.permission.INTERNET', manifest)
        self.assertNotIn('WRITE_EXTERNAL_STORAGE', manifest)

if __name__ == '__main__': unittest.main()
