# Java 21 unit-test status (parked)

Branch `9.4`, JDK 21.0.9 (macOS arm64), sweep of 2026-09-26. The product builds for Java 21 and passes the automated distribution, vulnerability and runtime smoke checks (`qa/cve-remediation/run_all.sh`). This report covers the unit tests only, which were parked part-way through the Mockito/PowerMock migration.

## Summary

| | |
|---|---|
| Tests run | 5,254 in 51 modules |
| Failing | **165** in 10 modules |
| Java 21 regressions | 142 (the other 23 also fail on Java 11 or depend on the machine) |
| Timed out | 2 engine classes produced no result: `PanCommandExecutorTest` and `PanTest`, killed after 180 s. Both install a SecurityManager to trap `System.exit`. |

Reproduce, with one JVM per test class and a timeout so a hang is reported instead of stalling the run:

```sh
export JAVA_HOME=<jdk-21>
mvn install -fae -Dmaven.test.failure.ignore=true -DreuseForks=false -DforkCount=3 -Dsurefire.timeout=180
python3 qa/java21/tools/collect.py && python3 qa/java21/tools/build_report.py
```

## Already done

- Root pom: `release 21`, compiler plugin 3.14.0, maven-bundle-plugin 6.0.0, jacoco 0.8.12.
- Byte Buddy managed to 1.17.8 (Mockito 3's Byte Buddy 1.11 can't instrument class version 65). That took kettle-core from 377 failures to 0 of 1,202.
- Test JVMs get the runtime `--add-opens` set (`jdk.add-opens.argLine`), extra opens PowerMock needs (`powermock.add-opens.argLine`) and `-Djava.security.manager=allow` for the Pan/Kitchen tests.
- engine moved to PowerMock 2.0.9 + `powermock-api-mockito2`: `verifyStatic(Class, mode)` (28 call sites), `org.powermock.reflect.Whitebox` (6 files), `MockitoHamcrest.argThat` (3 files), a typed `ArgumentMatcher` (1), `getArgument` (1), and `engine/src/test/resources/org/powermock/extensions/configuration.properties`.
- Java 21 test fixes: `XMLHandlerUnitTest` (the gzip header OS byte is 255 since Java 16), `SFTPClientTest` (`InetAddress` is sealed), `TableOutputTest` (null-safe matcher), and the snappy tests (stream closed before reading).

## Failures by module

| Module | Failing / tests | Mocking stack | Migrated |
|---|---:|---|---|
| `engine` | 126 / 2,437 | PowerMock 2.0.9 + Mockito 3.3.3 (migrated) | yes |
| `plugins/repositories/core` | 18 / 20 | mockito-all 1.9.5 | no |
| `ui` | 6 / 339 | PowerMock 1.7.3 + Mockito 1.10.19 | no |
| `plugins/pur/core` | 5 / 253 | PowerMock 1.7.3 + Mockito 1.10.19 | no |
| `plugins/ivw-bulk-loader/impl` | 4 / 9 | mockito-all 1.10.19 | no |
| `plugins/avro-format/core` | 2 / 42 | mockito-all 1.10.19 | no |
| `plugins/meta-inject/impl` | 1 / 17 | PowerMock 1.6.3 + mockito-all 1.10.19 | no |
| `plugins/pentaho-reporting/impl` | 1 / 8 | PowerMock 1.7.3 + Mockito 1.10.19 | no |
| `plugins/s3csvinput/core` | 1 / 13 | PowerMock 1.7.3 + Mockito 1.10.19 | no |
| `plugins/shapefilereader/core` | 1 / 1 | PowerMock 1.7.3 + Mockito 1.10.19 | no |

Modules not listed pass. `dbdialog`, `plugins/engine-configuration/impl` and `plugins/kafka/core` still use PowerMock 1.x but have no failing tests today; migrate them with the rest.

## Failures by cause

| Cause | Tests | Fix recipe |
|---|---:|---|
| **P1** PowerMock 1.x runner cannot start on Java 12+ | 12 | Move the module to PowerMock 2.0.9 + `powermock-api-mockito2` (managed in the root pom), as done for engine, then apply the other recipes to whatever surfaces. |
| **P4** javassist 3.20 cannot rewrite Java 11+ nested classes | 13 | Give test scope a current javassist (3.30.2-GA or later) in the PowerMock modules. Likely the quickest win. |
| **P3** PowerMock class loader vs JDK module boundaries | 3 | Copy engine's `configuration.properties` into each PowerMock module; add `@PowerMockIgnore` for the package named in the error, or open it in `powermock.add-opens.argLine`. |
| **M1** Mockito 2+ strict stubbing | 6 | Remove the unused stubs, or switch that class to `MockitoJUnitRunner.Silent`. |
| **M2** Mockito 2+ checked-exception validation | 4 | Throw an exception the method declares, or a RuntimeException. |
| **M3** Mockito 2+ matcher and verification differences | 16 | Use untyped `any()` or `nullable(Foo.class)` where the argument can be null (see the TableOutputTest fix). |
| **M5** A stubbed value comes back null | 46 | Find the stub for the null value named in the message and relax its matchers as in M3. |
| **M4** Assertion differences, root cause not yet isolated | 42 | Debug per class, starting with the classes that have the most failures. |
| **E** Environment or pre-existing (not Java 21 regressions) | 23 | Out of scope for the Java 21 work; see the Java 11 known failures in `qa/cve-remediation/TEST_PLAN.md` (A1). |

### P1. PowerMock 1.x runner cannot start on Java 12+ (12)

PowerMock 1.6/1.7 removes `final` through `Field.modifiers`, which Java 12+ hides (`Failed to find the "modifiers" field`). Every `@RunWith(PowerMockRunner.class)` class fails before any test runs.

**Fix:** Move the module to PowerMock 2.0.9 + `powermock-api-mockito2` (managed in the root pom), as done for engine, then apply the other recipes to whatever surfaces.

| Module | Test class | Failing | First error |
|---|---|---:|---|
| `plugins/pur/core` | `JUnit4Provider` | 5 | `RuntimeException` Internal error: Failed to find the "modifiers" field in method setInternalState. |
| `ui` | `JUnit4Provider` | 4 | `RuntimeException` Internal error: Failed to find the "modifiers" field in method setInternalState. |
| `plugins/pentaho-reporting/impl` | `JUnit4Provider` | 1 | `RuntimeException` Internal error: Failed to find the "modifiers" field in method setInternalState. |
| `plugins/s3csvinput/core` | `JUnit4Provider` | 1 | `RuntimeException` Internal error: Failed to find the "modifiers" field in method setInternalState. |
| `plugins/shapefilereader/core` | `JUnit4Provider` | 1 | `RuntimeException` Internal error: Failed to find the "modifiers" field in method setInternalState. |

### P4. javassist 3.20 cannot rewrite Java 11+ nested classes (13)

PowerMock rewrites the classes named in `@PrepareForTest` with javassist. Every module resolves javassist 3.20.0-GA (pinned at compile scope by kettle-core), which predates Java 11 nest-mate attributes, so rewritten Java 21 classes break: `IllegalAccessError ... private field`, `ClassFormatError: Nest member ... bad constant type`, `Failed to transform class`.

**Fix:** Give test scope a current javassist (3.30.2-GA or later) in the PowerMock modules. Likely the quickest win.

| Module | Test class | Failing | First error |
|---|---|---:|---|
| `engine` | `TransTest` | 10 | `IllegalAccessError` class org.pentaho.di.trans.Trans tried to access private field org.pentaho.di.trans.Trans$BitMaskStatus.mask (org.pentaho.di.trans.Trans and |
| `plugins/meta-inject/impl` | `MetaInjectTest` | 1 | `IllegalStateException` Failed to transform class with name org.pentaho.di.trans.steps.metainject.MetaInjectTest. Reason: [source error] the called constructor is p |
| `ui` | `ControlSpaceKeyAdapterTest` | 1 | `ClassFormatError` Nest member class_info_index 764 has bad constant type in class file org/pentaho/di/ui/core/PropsUI |
| `ui` | `JobEntryJobDialogTest` | 1 | `ClassFormatError` Nest member class_info_index 764 has bad constant type in class file org/pentaho/di/ui/core/PropsUI |

### P3. PowerMock class loader vs JDK module boundaries (3)

Classes reloaded by PowerMock sit in the unnamed module and can no longer reach JDK internals (`javax.xml.parsers.FactoryFinder`, `java.util.regex`, `java.util.stream`, ...). Mostly fixed in engine by `src/test/resources/org/powermock/extensions/configuration.properties` plus the test-only `--add-opens` in the root pom; what remains depends on test order.

**Fix:** Copy engine's `configuration.properties` into each PowerMock module; add `@PowerMockIgnore` for the package named in the error, or open it in `powermock.add-opens.argLine`.

| Module | Test class | Failing | First error |
|---|---|---:|---|
| `engine` | `StepWithMappingMetaTest` | 1 | `InaccessibleObjectException` Unable to make public sun.nio.fs.UnixPath sun.nio.fs.UnixPath.getName(int) accessible: module java.base does not "opens sun.nio.fs" to unnam |
| `engine` | `TransTest` | 1 | `Exception` Unexpected exception, expected<org.pentaho.di.core.exception.KettleException> but was<java.lang.IllegalAccessError> |
| `engine` | `ExecuteTransServletTest` | 1 | `InaccessibleObjectException` Unable to make protected java.util.Collection java.util.concurrent.locks.ReentrantReadWriteLock.getQueuedWriterThreads() accessible: module  |

### M1. Mockito 2+ strict stubbing (6)

`MockitoJUnitRunner` now fails a class that stubs calls the test never makes (`UnnecessaryStubbingException`).

**Fix:** Remove the unused stubs, or switch that class to `MockitoJUnitRunner.Silent`.

| Module | Test class | Failing | First error |
|---|---|---:|---|
| `engine` | `MetaFileLoaderImplTest` | 1 | `UnnecessaryStubbingException`  Unnecessary stubbings detected in test class: MetaFileLoaderImplTest Clean & maintainable test code requires zero unnecessary code. Followi |
| `engine` | `RepositoryImporterTest` | 1 | `UnnecessaryStubbingException`  Unnecessary stubbings detected in test class: RepositoryImporterTest Clean & maintainable test code requires zero unnecessary code. Followi |
| `engine` | `BaseStreamStepMetaTest` | 1 | `UnnecessaryStubbingException`  Unnecessary stubbings detected in test class: BaseStreamStepMetaTest Clean & maintainable test code requires zero unnecessary code. Followi |
| `engine` | `BaseStreamStepTest` | 1 | `UnnecessaryStubbingException`  Unnecessary stubbings detected in test class: BaseStreamStepTest Clean & maintainable test code requires zero unnecessary code. Following s |
| `engine` | `FixedTimeStreamWindowTest` | 1 | `UnnecessaryStubbingException`  Unnecessary stubbings detected in test class: FixedTimeStreamWindowTest Clean & maintainable test code requires zero unnecessary code. Foll |
| `engine` | `RunTransServletTest` | 1 | `UnnecessaryStubbingException`  Unnecessary stubbings detected in test class: RunTransServletTest Clean & maintainable test code requires zero unnecessary code. Following  |

### M2. Mockito 2+ checked-exception validation (4)

`doThrow(new Exception())` / `thenThrow(new IOException())` on a method that doesn't declare that exception is now rejected.

**Fix:** Throw an exception the method declares, or a RuntimeException.

| Module | Test class | Failing | First error |
|---|---|---:|---|
| `engine` | `JobEntryJobRunnerTest` | 2 | `MockitoException`  Checked exception is invalid for this method! Invalid: java.lang.Exception |
| `engine` | `AbstractMetaTest` | 1 | `MockitoException`  Checked exception is invalid for this method! Invalid: org.pentaho.di.core.exception.KettleException: null  |
| `engine` | `RegisterPackageServletTest` | 1 | `MockitoException`  Checked exception is invalid for this method! Invalid: java.io.IOException |

### M3. Mockito 2+ matcher and verification differences (16)

`any(Foo.class)`, `anyString()` and similar no longer match `null`, so stubs silently stop applying and verifications see different arguments.

**Fix:** Use untyped `any()` or `nullable(Foo.class)` where the argument can be null (see the TableOutputTest fix).

| Module | Test class | Failing | First error |
|---|---|---:|---|
| `engine` | `JobEntryDeleteFilesTest` | 3 | `ArgumentsAreDifferent`  Argument(s) are different! Wanted: jobEntryDeleteFiles.processFile( <any string>, <any string>, <any org.pentaho.di.job.Job> ); -> at org.p |
| `engine` | `SimpleMappingTest` | 2 | `KettleException`  java.lang.ClassCastException: class org.mockito.codegen.Object cannot be cast to class org.pentaho.di.core.row.RowMetaInterface (org.mockit |
| `engine` | `TableOutputTest` | 2 | `ArgumentsAreDifferent`  Argument(s) are different! Wanted: database.truncateTable( <any string>, <any string> ); -> at org.pentaho.di.trans.steps.tableoutput.Table |
| `engine` | `JobEntryCopyFilesTest` | 1 | `ArgumentsAreDifferent`  Argument(s) are different! Wanted: namedClusterEmbedManager.passEmbeddedMetastoreKey( <any>, <any string> ); -> at org.pentaho.di.job.entri |
| `engine` | `JobEntryJobRunnerTest` | 1 | `ArgumentsAreDifferent`  Argument(s) are different! Wanted: result.setNrErrors(<any integer>); -> at org.pentaho.di.job.entries.job.JobEntryJobRunnerTest.testRunWit |
| `engine` | `KettleDatabaseRepositoryStepDelegateUnitTest` | 1 | `ArgumentsAreDifferent`  Argument(s) are different! Wanted: kettleDatabaseRepositoryConnectionDelegate.getIDsWithValues( <any string>, <any string>, <any string>, ) |
| `engine` | `SharedObjectsTest` | 1 | `WantedButNotInvoked`  Wanted but not invoked: sharedObjectsMock.restoreFileFromBackup( <any string> ); -> at org.pentaho.di.shared.SharedObjectsTest.writeToFileT |
| `engine` | `StepWithMappingMetaTest` | 1 | `ArgumentsAreDifferent`  Argument(s) are different! Wanted: transMeta.setFilename( "${Internal.Entry.Current.Directory}/test" ); -> at org.pentaho.di.trans.StepWith |
| `engine` | `TransTest` | 1 | `ArgumentsAreDifferent`  Argument(s) are different! Wanted: stepInterface.dispose( <any org.pentaho.di.trans.step.StepMetaInterface>, <any org.pentaho.di.trans.step |
| `engine` | `TransSplitterTest` | 1 | `WantedButNotInvoked`  Wanted but not invoked: repository.readTransSharedObjects( Mock for TransMeta, hashCode: 1939292118 ); -> at org.pentaho.di.trans.cluster.T |
| `engine` | `DatabaseLookupUTest` | 1 | `WantedButNotInvoked`  Wanted but not invoked: database.getLookup( <any java.sql.PreparedStatement>, <any boolean>, false ); -> at org.pentaho.di.trans.steps.data |
| `engine` | `FuzzyMatchTest` | 1 | `ClassCastException` class org.mockito.codegen.Object cannot be cast to class org.pentaho.di.core.row.RowMetaInterface (org.mockito.codegen.Object and org.pentah |

### M5. A stubbed value comes back null (46)

Code under test hits `null` where the test stubbed a value (`Cannot invoke ... because "x" is null`, an unexpected NullPointerException, `but: null`). Under Mockito 3 the stub no longer applies, usually because its matcher no longer matches the real (sometimes null) argument, so the mock returns its default `null`. Same root cause as M3, seen from the other side.

**Fix:** Find the stub for the null value named in the message and relax its matchers as in M3.

| Module | Test class | Failing | First error |
|---|---|---:|---|
| `engine` | `JobEntryJobTest` | 15 | `KettleException`  Unexpected error during job metadata load !JobTrans.Exception.MetaDataLoad! Cannot invoke "org.pentaho.di.core.variables.VariableSpace.envi |
| `engine` | `TransExecutorUnitTest` | 7 | `KettleException`  There was an unexpected error: Cannot invoke "org.pentaho.di.trans.TransMeta.getPreviousResult()" because "this.transMeta" is null  |
| `engine` | `SlaveServerTest` | 4 | `Exception` Unexpected exception, expected<org.pentaho.di.core.exception.KettleException> but was<java.lang.NullPointerException> |
| `engine` | `RepositoryExporterTest` | 3 | `KettleException`  Error while exporting repository transformations Cannot invoke "org.pentaho.di.trans.TransMeta.setRepository(org.pentaho.di.repository.Repo |
| `engine` | `DatabaseLookupUTest` | 3 | `AssertionError`  Expected: is an instance of org.pentaho.di.trans.steps.databaselookup.readallcache.ReadAllCache but: null |
| `engine` | `SerializationHelperTest` | 2 | `Exception` Unexpected exception, expected<org.pentaho.di.core.exception.KettleException> but was<java.lang.NullPointerException> |
| `engine` | `JobTest` | 2 | `NullPointerException` Cannot invoke "org.pentaho.di.core.database.Database.isAutoCommit()" because "db" is null |
| `engine` | `JobExecutorTest` | 2 | `KettleException`  There was an unexpected error: Cannot invoke "org.pentaho.di.job.JobMeta.getContainerObjectId()" because "jobMeta" is null  |
| `engine` | `SwitchCaseTest` | 2 | `NullPointerException` Cannot invoke "java.util.Set.size()" because "ones" is null |
| `engine` | `JobEntryTransTest` | 1 | `KettleException`  !JobTrans.Exception.MetaDataLoad! Cannot invoke "org.pentaho.di.core.variables.VariableSpace.getClass()" because "parentVariables" is null  |
| `engine` | `KettleDatabaseRepositoryCreationHelperTest` | 1 | `NullPointerException` Cannot invoke "String.toUpperCase()" because "sql" is null |
| `engine` | `TransTest` | 1 | `KettleException`  Unable to write information to the step log table Cannot invoke "org.pentaho.di.core.database.DatabaseMeta.shareVariablesWith(org.pentaho.d |
| `engine` | `GroupByTest` | 1 | `NullPointerException`  |
| `engine` | `RestTest` | 1 | `KettleException`  Can not result from [null] Cannot invoke "com.sun.jersey.api.client.WebResource.getRequestBuilder()" because "webResource" is null  |
| `engine` | `ExecuteJobServletTest` | 1 | `NullPointerException` Cannot invoke "org.pentaho.di.job.JobMeta.listParameters()" because "jobMeta" is null |

### M4. Assertion differences, root cause not yet isolated (42)

The tests run but assert a different result. Most look like M3 underneath (a stub that stopped matching returns a default `null` or empty value), but each needs a look.

**Fix:** Debug per class, starting with the classes that have the most failures.

| Module | Test class | Failing | First error |
|---|---|---:|---|
| `engine` | `StreamLookupTest` | 8 | `AssertionFailedError` Output row is of wrong size expected:<3> but was:<2> |
| `engine` | `JobEntryDeleteFilesTest` | 6 | `AssertionError` expected:<2> but was:<6> |
| `engine` | `DatabaseLookupUTest` | 5 | `AssertionError`  |
| `plugins/ivw-bulk-loader/impl` | `IngresVectorwiseTest` | 4 | `AssertionError` expected:<2> but was:<0> |
| `engine` | `AttributesUtilTest` | 2 | `AssertionError`  |
| `engine` | `SwitchCaseTest` | 2 | `AssertionError` expected:<1> but was:<0> |
| `engine` | `MetricsPainterTest` | 1 | `AssertionError` Expected exception: java.lang.IllegalArgumentException |
| `engine` | `JobEntryColumnsExistTest` | 1 | `AssertionError` Should be no error expected:<0> but was:<1> |
| `engine` | `MailConnectionTest` | 1 | `AssertionError` Original file name should be tried first. |
| `engine` | `KitchenCommandExecutorTest` | 1 | `AssertionError`  |
| `engine` | `StepWithMappingMetaTest` | 1 | `KettleException`  Unable to load transformation [{0}] : can't find directory The transformation path /testFolder/CDA-91/testTrans.ktr is invalid, and will no |
| `engine` | `TransMetaTest` | 1 | `ComparisonFailure` expected:<[overridden]> but was:<[value]> |
| `engine` | `TransTest` | 1 | `RuntimeException` test should never throw an exception to this level |
| `engine` | `PDI5436Test` | 1 | `KettleStepException`  Unable to determine the fields of table [null]  |
| `engine` | `FieldSplitterTest` | 1 | `AssertionError` Output row is of an unexpected length expected:<4> but was:<3> |
| `engine` | `HTTPPOSTTest` | 1 | `AssertionError` expected:<u=usr&p=pass> but was:<null> |
| `engine` | `JobExecutorMetaTest` | 1 | `KettleException`  It was not possible to load the specified job  |
| `engine` | `ParseMailInputTest` | 1 | `AssertionError` Message Attached files count is correct expected:<3> but was:<0> |
| `engine` | `SFTPPutTest` | 1 | `KettleException`  Error connecting! For a SFTP connection server name and username must be set and server port must be greater than zero.  |
| `engine` | `TableInputMetaTest` | 1 | `ComparisonFailure` expected:<[[field1 String<binary-string>]]> but was:<[]> |
| `engine` | `PDI_11152_Test` | 1 | `AssertionFailedError` Failure during row processing |

### E. Environment or pre-existing (not Java 21 regressions) (23)

Also fail on Java 11, or depend on the machine: port 55555 in use, a real SMTP server, avro's snappy 1.1.1.3 having no Apple Silicon native library, the repository tests' log-store setup, PowerMock in the reporting plugin.

**Fix:** Out of scope for the Java 21 work; see the Java 11 known failures in `qa/cve-remediation/TEST_PLAN.md` (A1).

| Module | Test class | Failing | First error |
|---|---|---:|---|
| `plugins/repositories/core` | `RepositoryConnectControllerTest` | 13 | `NoClassDefFoundError` Could not initialize class org.pentaho.di.ui.repo.controller.RepositoryConnectController |
| `plugins/repositories/core` | `RepositorySessionTimeoutHandlerTest` | 3 | `NoClassDefFoundError` Could not initialize class org.pentaho.di.ui.repo.controller.RepositoryConnectController$$EnhancerByMockitoWithCGLIB$$33a50b |
| `engine` | `MailTest` | 2 | `KettleException`  Can not send mail! 530-5.7.0 Must issue a STARTTLS command first. For more information, go to 530-5.7.0 https://support.google.com/a/answer |
| `plugins/repositories/core` | `SessionTimeoutHandlerTest` | 2 | `ExceptionInInitializerError` Caused by: java.lang.RuntimeException: Central Log Store is not initialized!!! |
| `engine` | `HTTPProtocolTest` | 1 | `FatalStartupException` java.lang.RuntimeException: java.io.IOException: Failed to bind to /0.0.0.0:55555 |
| `plugins/avro-format/core` | `AvroInputMetaTest` | 1 | `AssertionError` Expected exception: org.pentaho.di.core.exception.KettleStepException |
| `plugins/avro-format/core` | `PentahoAvroReadWriteTest` | 1 | `SnappyError` [FAILED_TO_LOAD_NATIVE_LIBRARY] no native library is found for os.name=Mac and os.arch=aarch64 |

## Suggested order when resuming

1. P4: a test-scoped javassist 3.30.x override is one pom change and should clear the TransTest, UI and meta-inject class-format errors.
2. P1: migrate `ui`, `dbdialog`, `plugins/pur/core`, `plugins/meta-inject/impl`, `plugins/s3csvinput/core`, `plugins/shapefilereader/core`, `plugins/pentaho-reporting/impl`, `plugins/engine-configuration/impl` and `plugins/kafka/core` to PowerMock 2 the way engine was done.
3. M1, M2 and M3: mechanical, one class at a time.
4. M4 and the two timed-out Pan classes: investigate individually.
5. Longer term: `master` removed PowerMock from almost all tests (82 files use it here, 11 there). Porting master's versions of these tests is an alternative to fixing them in place.
