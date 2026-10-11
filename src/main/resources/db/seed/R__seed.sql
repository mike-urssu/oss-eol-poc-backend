-- 초기 대상 6종 (REG-03). 출처 URL과 설정은 아직 검증 전이다(설계 12장 M7에서 확인 후 수정).
-- repeatable migration이므로 이 파일을 고치면 다음 기동 시 다시 적용된다.
INSERT INTO opensource (slug, name, homepage_url) VALUES
    ('postgresql',   'PostgreSQL',    'https://www.postgresql.org'),
    ('openssl',      'OpenSSL',       'https://openssl-library.org'),
    ('tomcat',       'Apache Tomcat', 'https://tomcat.apache.org'),
    ('openssh',      'OpenSSH',       'https://www.openssh.com'),
    ('linux-kernel', 'Linux kernel',  'https://www.kernel.org'),
    ('spring-boot',  'Spring Boot',   'https://spring.io/projects/spring-boot')
ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, homepage_url = EXCLUDED.homepage_url;

INSERT INTO source_registry (opensource_id, url, collector_type, trust_level, config)
SELECT o.id, s.url, s.collector_type, 'OFFICIAL', s.config::jsonb
FROM (VALUES
    ('postgresql',   'https://www.postgresql.org/support/versioning/', 'WEB',
     '{"selector": "table", "versionHint": "메이저 버전. 10 이상은 정수(예: 17), 10 미만은 두 자리(예: 9.6). 17.6, 9.6.24 같은 패치 버전은 latestPatchVersion으로", "versionPattern": "\\d{2,}|\\d\\.\\d+"}'),
    ('openssl',      'https://openssl-library.org/policies/releasestrat/index.html', 'WEB',
     '{"versionHint": "릴리스 시리즈(예: 3.0, 3.5)", "versionPattern": "\\d+\\.\\d+"}'),
    ('tomcat',       'https://tomcat.apache.org/whichversion.html', 'WEB',
     '{"versionHint": "메이저.마이너 시리즈(예: 10.1)", "versionPattern": "\\d+\\.\\d+"}'),
    ('openssh',      'https://www.openssh.com/releasenotes.html', 'WEB',
     '{"versionHint": "릴리스 버전(예: 9.9). 포터블 접미사 p1 등은 제외"}'),
    ('linux-kernel', 'https://www.kernel.org/releases.json', 'API',
     '{"versionHint": "longterm 브랜치(예: 6.6). 업스트림 커널만", "versionPattern": "\\d+\\.\\d+"}'),
    ('spring-boot',  'https://api.spring.io/projects/spring-boot/generations', 'API',
     '{"versionHint": "마이너 시리즈(예: 3.5). OSS 지원 종료일만, 상용 지원은 제외"}')
) AS s(slug, url, collector_type, config)
JOIN opensource o ON o.slug = s.slug
ON CONFLICT (opensource_id, url) DO UPDATE SET collector_type = EXCLUDED.collector_type, config = EXCLUDED.config;
