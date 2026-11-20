INSERT INTO templates (id, template_key, release_no, name, state, display_position, preview, fonts, layouts, schema_version)
VALUES
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00003'), 'editorial', 1, '에디토리얼', 'published', 2,
     JSON_OBJECT('backgroundColor', '#F8F4EB', 'headingColor', '#28232B', 'description', '따뜻한 종이색 배경과 큰 제목, 글과 여백 중심'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00004'), 'pitch-dark', 1, '다크 피치', 'published', 3,
     JSON_OBJECT('backgroundColor', '#121827', 'headingColor', '#A9CBFF', 'description', '어두운 남색 배경과 밝은 강조색, 제안과 성과 발표'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00005'), 'warm-story', 1, '웜 스토리', 'published', 4,
     JSON_OBJECT('backgroundColor', '#FFF1E3', 'headingColor', '#6A3729', 'description', '살구색 배경과 부드러운 윤곽, 사례와 이야기 중심'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00006'), 'grid-analysis', 1, '그리드 분석', 'published', 5,
     JSON_OBJECT('backgroundColor', '#EAF5F1', 'headingColor', '#174C43', 'description', '연한 녹색 배경과 정돈된 분할, 수치와 근거 비교'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00007'), 'bold-cover', 1, '볼드 커버', 'published', 6,
     JSON_OBJECT('backgroundColor', '#FCE25B', 'headingColor', '#232329', 'description', '선명한 노란색과 굵은 제목, 표지와 핵심 메시지 강조'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1);
