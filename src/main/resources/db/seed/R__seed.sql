-- 초기 대상 6종 (REG-03). 출처는 endoflife.date/{slug} 하나로 통일한다(요구사항 9장).
-- repeatable migration이므로 이 파일을 고치면 다음 기동 시 다시 적용된다. 모든 문장은 여러 번 실행해도 결과가 같아야 한다.

-- endoflife.date와 slug를 맞춘다. 이미 바뀌었으면 아무것도 하지 않는다.
UPDATE opensource SET slug = 'linux'
WHERE slug = 'linux-kernel' AND NOT EXISTS (SELECT 1 FROM opensource WHERE slug = 'linux');

INSERT INTO opensource (slug, name, homepage_url) VALUES
    ('postgresql',  'PostgreSQL',    'https://www.postgresql.org'),
    ('openssl',     'OpenSSL',       'https://openssl-library.org'),
    ('tomcat',      'Apache Tomcat', 'https://tomcat.apache.org'),
    ('openssh',     'OpenSSH',       'https://www.openssh.com'),
    ('linux',       'Linux kernel',  'https://www.kernel.org'),
    ('spring-boot', 'Spring Boot',   'https://spring.io/projects/spring-boot')
ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, homepage_url = EXCLUDED.homepage_url;

-- 예전 공식 사이트 출처는 수집하지 않는다. 수집 이력과 근거가 참조하므로 지우지 않고 비활성화한다.
-- OpenSSH는 endoflife.date에 페이지가 없어 활성 출처가 남지 않는다.
UPDATE source_registry SET active = false WHERE url NOT LIKE 'https://endoflife.date/%';
-- selector 설정은 더 이상 쓰지 않는다(본문은 AI가 web fetch로 가져온다).
UPDATE source_registry SET config = config - 'selector' WHERE config->>'selector' IS NOT NULL;

-- 버전은 endoflife.date 표의 Release 값을 그대로 쓴다.
INSERT INTO source_registry (opensource_id, url, collector_type, trust_level, config)
SELECT o.id, 'https://endoflife.date/' || o.slug, 'WEB', 'OFFICIAL', s.config::jsonb
FROM (VALUES
    ('postgresql',
     '{"versionHint": "표의 Release 값(예: 17, 9.6). 17.6, 9.6.24 같은 패치 버전은 latestPatchVersion으로", "versionPattern": "\\d{2,}|\\d\\.\\d+"}'),
    ('openssl',
     '{"versionHint": "표의 Release 값(예: 3.5, 1.1.1)", "versionPattern": "\\d+\\.\\d+(\\.\\d+)?"}'),
    ('tomcat',
     '{"versionHint": "표의 Release 값(예: 11.0, 10.1, 7)", "versionPattern": "\\d+(\\.\\d+)?"}'),
    ('linux',
     '{"versionHint": "표의 Release 값(예: 6.12)", "versionPattern": "\\d+\\.\\d+"}'),
    ('spring-boot',
     '{"versionHint": "표의 Release 값(예: 3.5). OSS 지원 종료일만, 상용 지원은 제외", "versionPattern": "\\d+\\.\\d+"}')
) AS s(slug, config)
JOIN opensource o ON o.slug = s.slug
ON CONFLICT (opensource_id, url) DO UPDATE
SET collector_type = EXCLUDED.collector_type, trust_level = EXCLUDED.trust_level, config = EXCLUDED.config;
