"""验证全文覆盖与 EPUB 阅读顺序，不调用远程模型。"""
import io
import unittest
import zipfile
from analyze_ebook_corpus import chunks, extract


class CorpusTests(unittest.TestCase):
    def test_contiguous_complete_chunks(self):
        source = 'abc\n' + '\u6d4b\u8bd5\U0001f600' * 10000
        parts = list(chunks(source))
        self.assertEqual(source, ''.join(p[2] for p in parts))
        self.assertEqual(parts[0][0], 0)
        self.assertEqual(parts[-1][1], len(source))
        for previous, following in zip(parts, parts[1:]):
            self.assertEqual(previous[1], following[0])
        self.assertTrue(all(len(p[2].encode()) <= 13500 for p in parts))

    def test_strict_txt_decode(self):
        source = '\u4e2d\u6587\u5b8c\u6574\u6b63\u6587'
        self.assertEqual(extract(source.encode('gb18030'), 'txt')[0], source)
        self.assertEqual(extract(source.encode('utf-16'), 'txt')[0], source)
        self.assertEqual(extract(source.encode('utf-32'), 'txt')[0], source)
        with self.assertRaises(UnicodeDecodeError):
            b'\xff'.decode('utf-8')

    def test_epub_spine_not_zip_order(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('META-INF/container.xml', '<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>')
            archive.writestr('OPS/book.opf', '<package><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>')
            archive.writestr('OPS/a.xhtml', '<p>SECOND</p><script>OMIT</script>')
            archive.writestr('OPS/b.xhtml', '<p>FIRST</p>')
        text, metadata = extract(stream.getvalue(), 'epub')
        self.assertLess(text.index('FIRST'), text.index('SECOND'))
        self.assertNotIn('OMIT', text)
        self.assertEqual(metadata['spineResources'], 2)

    def test_unsupported_format(self):
        with self.assertRaises(ValueError):
            extract(b'data', 'mobi')


if __name__ == '__main__':
    unittest.main()
