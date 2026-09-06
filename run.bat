@ECHO OFF
CALL .\gradlew.bat installDist
IF ERRORLEVEL 1 (
    ECHO.
    ECHO Build failed; not launching TradeyCLI.
    EXIT /B 1
)
:: UTF-8 output, or the console shows the glyphs as question marks.
CHCP 65001 >NUL
CALL .\build\install\TradeyCLI\bin\TradeyCLI.bat %*
