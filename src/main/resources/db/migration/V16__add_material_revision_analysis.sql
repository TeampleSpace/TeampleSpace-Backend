-- 자료 AI 분석 정리 결과. 개요, 주요 논점과 근거, 발표 포인트를 담는다
ALTER TABLE material_revisions ADD COLUMN analysis JSON NULL AFTER summary;
