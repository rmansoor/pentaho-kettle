@echo off
REM Activate this PDI installation and check its license, entirely offline. Run without arguments for help:
REM   License.bat request <license key>      print the request code for this machine
REM   License.bat install <activation code>  install the activation code issued for it
REM   License.bat status ^| deactivate

setlocal
call "%~dp0set-pentaho-env.bat"
set LICENSE_JAR=
for %%f in ("%~dp0lib\kettle-license-*.jar") do set "LICENSE_JAR=%%~ff"
if "%LICENSE_JAR%"=="" (
  echo kettle-license jar not found in %~dp0lib
  exit /b 2
)
"%_PENTAHO_JAVA%" -cp "%LICENSE_JAR%" org.pentaho.di.license.cli.LicenseCli %*
exit /b %ERRORLEVEL%
