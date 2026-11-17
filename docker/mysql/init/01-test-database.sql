-- 테스트 전용 DB
CREATE DATABASE IF NOT EXISTS teamplespace_test;
GRANT ALL PRIVILEGES ON teamplespace_test.* TO 'teamplespace'@'%';
