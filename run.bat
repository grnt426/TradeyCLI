CALL .\gradlew.bat installDist
IF ERRORLEVEL 1 (
    ECHO.
    ECHO Build failed; not launching TradeyCLI.
    EXIT /B 1
)
cls
CALL .\build\install\TradeyCLI\bin\TradeyCLI.bat
