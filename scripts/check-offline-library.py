#!/usr/bin/env python3
"""Check that real FTS results resolve to readable passages in the shipped archive."""
import sqlite3
import sys
import zipfile
from pathlib import Path

archive, index = map(Path, sys.argv[1:3])
con = sqlite3.connect(f'file:{index}?mode=ro', uri=True)
with zipfile.ZipFile(archive) as z:
    for query in ('"survival" "tips"', '"water" OR "purification"'):
        rows = con.execute("""SELECT d.title,f.path,p.offset,p.length FROM fts
            JOIN passages p ON p.id=fts.rowid JOIN docs d ON d.id=p.doc_id
            JOIN files f ON f.id=d.file_id WHERE fts MATCH ?
            AND rank MATCH 'bm25(4.0, 1.0)' ORDER BY rank LIMIT 3""", (query,)).fetchall()
        assert rows, f'No FTS results: {query}'
        for title, path, offset, length in rows:
            with z.open('text/' + path) as shard:
                shard.seek(offset)
                passage = shard.read(length).decode('utf-8').strip()
            assert title and passage, (title, path, offset, length)
        print(f'{query}: {len(rows)} passages readable; first title: {rows[0][0]}')
source=Path('app/src/main/java/com/localchat/runtime/SurvivalLibrarySearch.kt').read_text()
assert 'File(root, "text").canonicalFile' in source and 'File(textRoot, path).canonicalFile' in source
