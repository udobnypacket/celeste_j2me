@echo off
rem 1) Установи JDK 8 (или 7) и Sun WTK 2.5.2. 2) Поправь два пути ниже. 3) Запусти build.bat
set JDK=C:\Program Files\Java\jdk1.8.0_202
set WTK=C:\WTK2.5.2_01
set API=%WTK%\lib\midpapi20.jar;%WTK%\lib\cldcapi11.jar
if exist build rmdir /s /q build
mkdir build\classes build\pre
"%JDK%\bin\javac" -source 1.3 -target 1.3 -bootclasspath "%API%" -d build\classes src\*.java || goto :err
"%WTK%\bin\preverify" -classpath "%API%" -d build\pre build\classes || goto :err
copy res\data.bin build\pre\ >nul
"%JDK%\bin\jar" cfm Celeste.jar MANIFEST.MF -C build\pre .
for %%A in (Celeste.jar) do set SZ=%%~zA
(
echo MIDlet-1: Celeste, , CelesteMIDlet
echo MIDlet-Name: Celeste Classic
echo MIDlet-Vendor: Port
echo MIDlet-Version: 1.0.0
echo MIDlet-Jar-URL: Celeste.jar
echo MIDlet-Jar-Size: %SZ%
echo MicroEdition-Configuration: CLDC-1.1
echo MicroEdition-Profile: MIDP-2.0
) > Celeste.jad
echo DONE: Celeste.jar + Celeste.jad
goto :eof
:err
echo BUILD FAILED
