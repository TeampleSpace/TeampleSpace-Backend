INSERT INTO templates (id, template_key, release_no, name, state, display_position, preview, fonts, layouts, schema_version)
VALUES
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00015'), 'burgundy-editorial', 1, '버건디 편집형', 'published', 14,
     JSON_OBJECT('backgroundColor', '#F3F0E8', 'headingColor', '#682A35', 'description', '차분한 버건디와 비교표, 근거를 중심으로 구성한 보고서'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00016'), 'tech-pitch', 1, '테크 피치', 'published', 15,
     JSON_OBJECT('backgroundColor', '#121819', 'headingColor', '#FFFFFF', 'description', '차콜과 라임으로 제품의 작동 방식과 성과를 설명하는 제안서'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00017'), 'open-campus', 1, '오픈 캠퍼스', 'published', 16,
     JSON_OBJECT('backgroundColor', '#285BDD', 'headingColor', '#FFFFFF', 'description', '코발트와 코랄, 큰 제목으로 조사 방법과 결과를 설명하는 수업 발표'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00018'), 'field-notes', 1, '필드 노트', 'published', 17,
     JSON_OBJECT('backgroundColor', '#F2F4E9', 'headingColor', '#254E40', 'description', '포레스트와 아이보리로 인터뷰, 비교표와 조사 결과를 정리하는 발표'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1);
