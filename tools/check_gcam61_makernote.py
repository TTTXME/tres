import base64
import gzip
import unittest
import zlib

from decode_gcam61_makernote import decode, jpeg_payload, transform


class MakerNoteTest(unittest.TestCase):
    def setUp(self):
        self.text = b'merged_frame_count: 30\n' * 17
        self.raw = b'HDRP\x02' + transform(gzip.compress(self.text, mtime=0))
        self.xmp = b'<x GCamera:hdrp_makernote="' + base64.b64encode(self.raw) + b'"/>'

    @staticmethod
    def jpeg(*segments):
        return b'\xff\xd8' + b''.join(b'\xff\xe1' + (len(s) + 2).to_bytes(2, 'big') + s for s in segments) + b'\xff\xd9'

    def test_decode_and_block_tail(self):
        self.assertEqual(decode(self.raw), self.text)
        self.assertEqual(transform(transform(bytes(range(255)))), bytes(range(255)))

    def test_invalid_input_and_limit(self):
        for raw in (b'', b'HDRP\x03abc', self.raw[:-1], self.raw + b'garbage'):
            with self.assertRaises((ValueError, zlib.error)):
                decode(raw)
        with self.assertRaises(ValueError):
            decode(self.raw, limit=10)

    def test_standard_xmp(self):
        self.assertEqual(jpeg_payload(self.jpeg(self.xmp)), self.raw)

    def test_chunked_extended_xmp(self):
        prefix = b'http://ns.adobe.com/xmp/extension/\0' + b'0' * 32 + len(self.xmp).to_bytes(4, 'big')
        parts = [prefix + p.to_bytes(4, 'big') + self.xmp[p:p + 19] for p in range(0, len(self.xmp), 19)]
        self.assertEqual(jpeg_payload(self.jpeg(*reversed(parts))), self.raw)
        for invalid in (parts[:-1], parts + parts[:1]):
            with self.assertRaises(ValueError):
                jpeg_payload(self.jpeg(*invalid))

    def test_missing_and_truncated(self):
        for raw in (b'not JPEG', self.jpeg(b'<x/>'), self.jpeg(self.xmp)[:-3]):
            with self.assertRaises(ValueError):
                jpeg_payload(raw)


if __name__ == '__main__':
    unittest.main()
