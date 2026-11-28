INSERT INTO templates (id, template_key, release_no, name, state, display_position, preview, fonts, layouts, schema_version)
VALUES
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00019'), 'pop-pitch', 1, '팝 피치', 'published', 18,
     JSON_OBJECT('backgroundColor', '#FFF7E8', 'headingColor', '#1D1B19', 'description', '크림 바탕과 노랑, 주황의 둥근 도형으로 밝게 제안하는 발표'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00020'), 'green-natural', 1, '그린 내추럴', 'published', 19,
     JSON_OBJECT('backgroundColor', '#F3EEE4', 'headingColor', '#1F2A24', 'description', '귀리색 바탕과 초록, 세이지의 아치와 원으로 차분하게 보고하는 발표'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00021'), 'swiss-grid', 1, '스위스 그리드', 'published', 20,
     JSON_OBJECT('backgroundColor', '#FFFFFF', 'headingColor', '#0A0A0A', 'description', '흰 바탕에 빨강과 검정, 격자와 큰 글자로 정리하는 발표'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00022'), 'bento-pastel', 1, '벤토 파스텔', 'published', 21,
     JSON_OBJECT('backgroundColor', '#F4F1FA', 'headingColor', '#221B33', 'description', '라일락 바탕에 크기가 다른 파스텔 타일로 아이디어를 나누는 발표'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00023'), 'corporate', 1, '코퍼레이트', 'published', 22,
     JSON_OBJECT('backgroundColor', '#0F2A4A', 'headingColor', '#FFFFFF', 'description', '남색과 금색, 제목 띠가 있는 격식 있는 보고서형 발표'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1);
