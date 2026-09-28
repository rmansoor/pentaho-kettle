#!/usr/bin/env python3
"""Turn qa/java21/failures.json (from collect.py) into qa/java21/UNIT_TEST_REPORT.md."""
import collections
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', 'failures.json')
OUT = os.path.join(HERE, '..', 'UNIT_TEST_REPORT.md')

# Test classes that also fail on Java 11 (see qa/cve-remediation/TEST_PLAN.md, A1)
PRE_JAVA11 = {
    ('plugins/avro-format/core', 'AvroInputMetaTest'), ('plugins/avro-format/core', 'PentahoAvroReadWriteTest'),
    ('plugins/repositories/core', 'RepositoryConnectControllerTest'),
    ('plugins/repositories/core', 'SessionTimeoutHandlerTest'),
    ('plugins/repositories/core', 'RepositorySessionTimeoutHandlerTest'),
    ('plugins/repositories/core', 'MetaStoreSessionTimeoutHandlerTest'),
    ('plugins/repositories/core', 'RepositoryServiceSessionTimeoutHandlerTest'),
    ('plugins/pentaho-reporting/impl', 'PentahoReportingOutputTest'),
    ('engine', 'HTTPProtocolTest'),
}

# Module -> test mocking stack at the time of the sweep
STACK = {
    'engine': 'PowerMock 2.0.9 + Mockito 3.3.3 (migrated)',
    'ui': 'PowerMock 1.7.3 + Mockito 1.10.19',
    'dbdialog': 'PowerMock 1.6.3 + mockito-all 1.10.19',
    'plugins/pur/core': 'PowerMock 1.7.3 + Mockito 1.10.19',
    'plugins/repositories/core': 'mockito-all 1.9.5',
    'plugins/meta-inject/impl': 'PowerMock 1.6.3 + mockito-all 1.10.19',
    'plugins/s3csvinput/core': 'PowerMock 1.7.3 + Mockito 1.10.19',
    'plugins/shapefilereader/core': 'PowerMock 1.7.3 + Mockito 1.10.19',
    'plugins/ivw-bulk-loader/impl': 'mockito-all 1.10.19',
    'plugins/pentaho-reporting/impl': 'PowerMock 1.7.3 + Mockito 1.10.19',
    'plugins/avro-format/core': 'mockito-all 1.10.19',
    'plugins/engine-configuration/impl': 'PowerMock 1.6.3 + mockito-all 1.10.19',
    'plugins/kafka/core': 'PowerMock 1.7.3 + mockito-all 1.10.19',
}

CATS = collections.OrderedDict([
    ('P1', ('PowerMock 1.x runner cannot start on Java 12+',
            'PowerMock 1.6/1.7 removes `final` through `Field.modifiers`, which Java 12+ hides '
            '(`Failed to find the "modifiers" field`). Every `@RunWith(PowerMockRunner.class)` class fails '
            'before any test runs.',
            'Move the module to PowerMock 2.0.9 + `powermock-api-mockito2` (managed in the root pom), as done '
            'for engine, then apply the other recipes to whatever surfaces.')),
    ('P4', ('javassist 3.20 cannot rewrite Java 11+ nested classes',
            'PowerMock rewrites the classes named in `@PrepareForTest` with javassist. Every module resolves '
            'javassist 3.20.0-GA (pinned at compile scope by kettle-core), which predates Java 11 nest-mate '
            'attributes, so rewritten Java 21 classes break: `IllegalAccessError ... private field`, '
            '`ClassFormatError: Nest member ... bad constant type`, `Failed to transform class`.',
            'Give test scope a current javassist (3.30.2-GA or later) in the PowerMock modules. '
            'Likely the quickest win.')),
    ('P3', ('PowerMock class loader vs JDK module boundaries',
            'Classes reloaded by PowerMock sit in the unnamed module and can no longer reach JDK internals '
            '(`javax.xml.parsers.FactoryFinder`, `java.util.regex`, `java.util.stream`, ...). Mostly fixed in '
            'engine by `src/test/resources/org/powermock/extensions/configuration.properties` plus the '
            'test-only `--add-opens` in the root pom; what remains depends on test order.',
            "Copy engine's `configuration.properties` into each PowerMock module; add `@PowerMockIgnore` for "
            'the package named in the error, or open it in `powermock.add-opens.argLine`.')),
    ('P2', ('Mockito 1.x (cglib) cannot mock the class on Java 21',
            'Mockito 1 generates mocks with cglib, which fails for some JDK and Java 21 classes.',
            'Move the module to Mockito 3 (via PowerMock 2 or plain mockito-core with Byte Buddy 1.17).')),
    ('J1', ('Java 21 sealed class',
            'Java 17+ sealed some JDK classes (e.g. `java.net.InetAddress`); they cannot be mocked.',
            'Use a real instance (see the SFTPClientTest fix).')),
    ('M1', ('Mockito 2+ strict stubbing',
            '`MockitoJUnitRunner` now fails a class that stubs calls the test never makes '
            '(`UnnecessaryStubbingException`).',
            'Remove the unused stubs, or switch that class to `MockitoJUnitRunner.Silent`.')),
    ('M2', ('Mockito 2+ checked-exception validation',
            "`doThrow(new Exception())` / `thenThrow(new IOException())` on a method that doesn't declare "
            'that exception is now rejected.',
            'Throw an exception the method declares, or a RuntimeException.')),
    ('M3', ('Mockito 2+ matcher and verification differences',
            '`any(Foo.class)`, `anyString()` and similar no longer match `null`, so stubs silently stop '
            'applying and verifications see different arguments.',
            'Use untyped `any()` or `nullable(Foo.class)` where the argument can be null (see the '
            'TableOutputTest fix).')),
    ('M5', ('A stubbed value comes back null',
            'Code under test hits `null` where the test stubbed a value (`Cannot invoke ... because "x" is '
            'null`, an unexpected NullPointerException, `but: null`). Under Mockito 3 the stub no longer '
            'applies, usually because its matcher no longer matches the real (sometimes null) argument, so '
            'the mock returns its default `null`. Same root cause as M3, seen from the other side.',
            'Find the stub for the null value named in the message and relax its matchers as in M3.')),
    ('M4', ('Assertion differences, root cause not yet isolated',
            'The tests run but assert a different result. Most look like M3 underneath (a stub that stopped '
            'matching returns a default `null` or empty value), but each needs a look.',
            'Debug per class, starting with the classes that have the most failures.')),
    ('E', ('Environment or pre-existing (not Java 21 regressions)',
           "Also fail on Java 11, or depend on the machine: port 55555 in use, a real SMTP server, avro's "
           'snappy 1.1.1.3 having no Apple Silicon native library, the repository tests\' log-store setup, '
           'PowerMock in the reporting plugin.',
           'Out of scope for the Java 21 work; see the Java 11 known failures in '
           '`qa/cve-remediation/TEST_PLAN.md` (A1).')),
])


def categorise(r):
    t, cls = r['type'], r['cls'].split('.')[-1]
    m = ' '.join((t, r['msg'], r['cause']))
    if (r['module'], cls) in PRE_JAVA11:
        return 'E'
    if any(k in m for k in ('BindException', 'Address already in use', 'STARTTLS', 'no native library',
                            'FAILED_TO_LOAD_NATIVE')):
        return 'E'
    if 'modifiers' in m and 'setInternalState' in m:
        return 'P1'
    if 'Nest member' in m or 'Failed to transform class' in m or ('IllegalAccessError' in t and 'private field' in m):
        return 'P4'
    if 'sealed' in m:
        return 'J1'
    if 'cglib' in m:
        return 'P2'
    if 'UnnecessaryStubbing' in t:
        return 'M1'
    if 'Checked exception is invalid' in m:
        return 'M2'
    if 'ArgumentsAreDifferent' in t or 'WantedButNotInvoked' in t or 'mockito.codegen.Object' in m:
        return 'M3'
    if (any(k in m for k in ('InaccessibleObjectException', 'IllegalAccessError', 'FactoryFinder',
                             'Could not initialize class', 'ExceptionInInitializerError'))
            or 'ExceptionInInitializerError' in t or 'NoClassDefFoundError' in t):
        return 'P3'
    if 'NullPointerException' in m or re.search(r'because "[^"]+" is null', m) or ' but: null' in m:
        return 'M5'
    return 'M4'


def main():
    d = json.load(open(DATA))
    rows, mods = d['rows'], d['modules']
    for r in rows:
        r['cat'] = categorise(r)
    cc = collections.Counter(r['cat'] for r in rows)
    total = sum(m['tests'] for m in mods.values())
    failing_mods = [k for k, m in mods.items() if m['failures'] + m['errors']]

    L = ['# Java 21 unit-test status (parked)', '',
         'Branch `9.4`, JDK 21.0.9 (macOS arm64), sweep of 2026-09-26. The product builds for Java 21 and '
         'passes the automated distribution, vulnerability and runtime smoke checks '
         '(`qa/cve-remediation/run_all.sh`). This report covers the unit tests only, which were parked '
         'part-way through the Mockito/PowerMock migration.', '',
         '## Summary', '',
         '| | |', '|---|---|',
         f'| Tests run | {total:,} in {len(mods)} modules |',
         f'| Failing | **{len(rows)}** in {len(failing_mods)} modules |',
         f'| Java 21 regressions | {len(rows) - cc["E"]} (the other {cc["E"]} also fail on Java 11 or depend '
         'on the machine) |',
         '| Timed out | 2 engine classes produced no result: `PanCommandExecutorTest` and `PanTest`, killed '
         'after 180 s. Both install a SecurityManager to trap `System.exit`. |', '',
         'Reproduce, with one JVM per test class and a timeout so a hang is reported instead of stalling '
         'the run:', '',
         '```sh',
         'export JAVA_HOME=<jdk-21>',
         'mvn install -fae -Dmaven.test.failure.ignore=true -DreuseForks=false -DforkCount=3 -Dsurefire.timeout=180',
         'python3 qa/java21/tools/collect.py && python3 qa/java21/tools/build_report.py',
         '```', '',
         '## Already done', '']
    for s in (
            'Root pom: `release 21`, compiler plugin 3.14.0, maven-bundle-plugin 6.0.0, jacoco 0.8.12.',
            "Byte Buddy managed to 1.17.8 (Mockito 3's Byte Buddy 1.11 can't instrument class version 65). "
            'That took kettle-core from 377 failures to 0 of 1,202.',
            'Test JVMs get the runtime `--add-opens` set (`jdk.add-opens.argLine`), extra opens PowerMock needs '
            '(`powermock.add-opens.argLine`) and `-Djava.security.manager=allow` for the Pan/Kitchen tests.',
            'engine moved to PowerMock 2.0.9 + `powermock-api-mockito2`: `verifyStatic(Class, mode)` '
            '(28 call sites), `org.powermock.reflect.Whitebox` (6 files), `MockitoHamcrest.argThat` (3 files), '
            'a typed `ArgumentMatcher` (1), `getArgument` (1), and '
            '`engine/src/test/resources/org/powermock/extensions/configuration.properties`.',
            'Java 21 test fixes: `XMLHandlerUnitTest` (the gzip header OS byte is 255 since Java 16), '
            '`SFTPClientTest` (`InetAddress` is sealed), `TableOutputTest` (null-safe matcher), and the snappy '
            'tests (stream closed before reading).'):
        L.append(f'- {s}')

    L += ['', '## Failures by module', '',
          '| Module | Failing / tests | Mocking stack | Migrated |', '|---|---:|---|---|']
    for k, m in sorted(mods.items(), key=lambda kv: -(kv[1]['failures'] + kv[1]['errors'])):
        n = m['failures'] + m['errors']
        if n:
            st = STACK.get(k, '')
            L.append(f'| `{k}` | {n} / {m["tests"]:,} | {st} | {"yes" if "migrated" in st else "no"} |')
    L += ['', 'Modules not listed pass. `dbdialog`, `plugins/engine-configuration/impl` and '
              '`plugins/kafka/core` still use PowerMock 1.x but have no failing tests today; migrate them '
              'with the rest.', '',
          '## Failures by cause', '',
          '| Cause | Tests | Fix recipe |', '|---|---:|---|']
    for k, (name, _, fix) in CATS.items():
        if cc.get(k):
            L.append(f'| **{k}** {name} | {cc[k]} | {fix} |')
    L.append('')

    for k, (name, why, fix) in CATS.items():
        if not cc.get(k):
            continue
        L += [f'### {k}. {name} ({cc[k]})', '', why, '', f'**Fix:** {fix}', '',
              '| Module | Test class | Failing | First error |', '|---|---|---:|---|']
        by_cls = collections.OrderedDict()
        for r in sorted((r for r in rows if r['cat'] == k), key=lambda r: (r['module'], r['cls'])):
            by_cls.setdefault((r['module'], r['cls']), []).append(r)
        for (mod, cls), rs in sorted(by_cls.items(), key=lambda kv: (-len(kv[1]), kv[0])):
            first = rs[0]
            msg = (first['msg'] or first['cause'] or '').replace('|', '\\|')
            msg = re.sub(r'\$MockitoMock\$\d+', '', msg)[:140]
            L.append(f'| `{mod}` | `{cls.split(".")[-1]}` | {len(rs)} | '
                     f'`{first["type"].split(".")[-1]}` {msg} |')
        L.append('')

    L += ['## Suggested order when resuming', '']
    for i, s in enumerate((
            'P4: a test-scoped javassist 3.30.x override is one pom change and should clear the TransTest, '
            'UI and meta-inject class-format errors.',
            'P1: migrate `ui`, `dbdialog`, `plugins/pur/core`, `plugins/meta-inject/impl`, '
            '`plugins/s3csvinput/core`, `plugins/shapefilereader/core`, `plugins/pentaho-reporting/impl`, '
            '`plugins/engine-configuration/impl` and `plugins/kafka/core` to PowerMock 2 the way engine was done.',
            'M1, M2 and M3: mechanical, one class at a time.',
            'M4 and the two timed-out Pan classes: investigate individually.',
            'Longer term: `master` removed PowerMock from almost all tests (82 files use it here, 11 there). '
            "Porting master's versions of these tests is an alternative to fixing them in place."), 1):
        L.append(f'{i}. {s}')

    open(OUT, 'w').write('\n'.join(L) + '\n')
    print(f'wrote {os.path.relpath(OUT)}: {len(rows)} failures', dict(cc))


if __name__ == '__main__':
    main()
