@echo off
rem Builds the double-clickable app image at dist\JRec\JRec.exe.
rem Needs JDK 17+ on PATH: jpackage ships with the JDK, nothing else to install.
rem The output is ~140 MB because it carries its own Java runtime. That is the point:
rem it runs on a machine with no Java installed at all.
rem
rem ASCII only on purpose. cmd.exe reads batch files in the OEM code page, and UTF-8
rem Chinese text here consumes the following quote or caret, which turns the script
rem into a stream of "is not recognized as an internal command" errors.
setlocal
cd /d "%~dp0"

set "SETTINGS="
if exist ".cache\settings.xml" set "SETTINGS=-s .cache\settings.xml"

echo [jrec] 1/3 compiling and packaging jars
rem install, not package: step 2 resolves jrec-core through the local repository, so the
rem reactor has to publish it there first. A clean clone has no jrec-core in ~/.m2 and
rem "package" would leave step 2 with an unresolvable dependency.
call mvn %SETTINGS% -B -q -DskipTests install
if errorlevel 1 goto fail

echo [jrec] 2/3 collecting runtime dependencies
if exist "jrec-desktop\target\dist-lib" rmdir /s /q "jrec-desktop\target\dist-lib"
call mvn %SETTINGS% -B -q -f jrec-desktop\pom.xml dependency:copy-dependencies -DoutputDirectory=target\dist-lib -DincludeScope=runtime
if errorlevel 1 goto fail

rem The platform-neutral JavaFX jars are 300-byte stubs; the -win ones carry the code.
rem The for-loop form tolerates "no match" without leaving a non-zero errorlevel behind.
for %%f in ("jrec-desktop\target\dist-lib\javafx-*-21.0.2.jar") do del /q "%%f"
copy /y "jrec-desktop\target\jrec-desktop-0.1.0.jar" "jrec-desktop\target\dist-lib\" >nul
if errorlevel 1 goto fail

echo [jrec] 3/3 building dist\JRec\JRec.exe
if exist "dist\JRec" rmdir /s /q "dist\JRec"
jpackage --type app-image --name JRec --dest dist --input jrec-desktop\target\dist-lib --main-jar jrec-desktop-0.1.0.jar --main-class com.rzx.jrec.desktop.Launcher --icon jrec-desktop\src\main\resources\com\rzx\jrec\desktop\app-icon.ico --app-version 0.1.0 --vendor fangtangj --java-options -Dfile.encoding=UTF-8 --description "Java screen recorder"
if errorlevel 1 goto fail

echo.
echo [jrec] done. Double-click dist\JRec\JRec.exe - no Java install needed.
exit /b 0

:fail
echo.
echo [jrec] build failed, see the output above.
exit /b 1
