DELETE ft
FROM file_tag ft
INNER JOIN local_file lf ON lf.id = ft.file_id
WHERE lf.deleted = 0
  AND ft.tag_name IN ('R18-是', 'R18-否')
  AND (
    lf.mime_type LIKE 'image/%'
    OR lf.mime_type LIKE 'video/%'
    OR LOWER(lf.extension) IN ('txt', 'epub', 'pdf', 'mobi', 'azw3', 'cbz', 'cbr')
  );

UPDATE local_file
SET adult_status = 0,
    adult_content = NULL,
    adult_confidence = NULL
WHERE deleted = 0
  AND (
    mime_type LIKE 'image/%'
    OR mime_type LIKE 'video/%'
    OR LOWER(extension) IN ('txt', 'epub', 'pdf', 'mobi', 'azw3', 'cbz', 'cbr')
  );
