@echo off
rem ChunkOps112 验证工具编译脚本（需要 JDK 8）
rem 用法: build-verifier.bat
setlocal
set JDK8=C:\Gradle\jdk8\bin
set SRC=%~dp0tools\verifier\src
set OUT=%~dp0tools\verifier\out
if not exist "%JDK8%\javac.exe" (
  echo [ERROR] JDK8 not found at %JDK8%
  exit /b 1
)
if not exist "%OUT%" mkdir "%OUT%"
dir /s /b "%SRC%\*.java" > "%TEMP%\verifier-src.txt"
"%JDK8%\javac.exe" -encoding UTF-8 -d "%OUT%" @"%TEMP%\verifier-src.txt"
if errorlevel 1 (
  echo [ERROR] compile failed
  exit /b 1
)
echo [OK] compiled to %OUT%
echo [usage]
echo   java -cp "%OUT%" com.chunkops.verify.NbtDump ^<file^> [maxDepth]
echo   java -cp "%OUT%" com.chunkops.verify.ChunkInspector ^<worldDir^> --scan
echo   java -cp "%OUT%" com.chunkops.verify.ChunkInspector ^<worldDir^> ^<chunkX^> ^<chunkZ^>
echo   java -cp "%OUT%" com.chunkops.verify.LevelDatInspector ^<worldDir^>
endlocal
