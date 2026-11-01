@echo off
setlocal
set "APP_HOME=%~dp0"
for %%i in ("%APP_HOME%.") do set "APP_HOME=%%~fi"
set "CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"

set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if defined ProgramFiles if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
if not defined JAVA_EXE set "JAVA_EXE=java.exe"

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" "-Dorg.gradle.wrapper.projectDir=%APP_HOME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
