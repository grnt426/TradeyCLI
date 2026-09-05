@ECHO OFF
:: The new console, from its own install folder so a running `run` keeps its jars.
CALL .\gradlew.bat installBridge
IF ERRORLEVEL 1 (
    ECHO.
    ECHO Build failed; not launching the bridge.
    EXIT /B 1
)
:: UTF-8 output, or the console shows the glyphs as question marks.
CHCP 65001 >NUL
CALL .\build\install-bridge\TradeyCLI\bin\TradeyCLI.bat bridge %*
