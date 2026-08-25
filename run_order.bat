@echo off
setlocal
cd /d "%~dp0"
set EASTCOM_ORDER_SIMULATOR_ENABLED=true

call mvn -B -pl samples -am -DskipTests install
if errorlevel 1 exit /b %errorlevel%

call mvn -B -f samples\pom.xml spring-boot:run "-Dspring-boot.run.main-class=dev.openan.workflow.engine.examples.demo.SpringSpnDemo" "-Dspring-boot.run.arguments=--a2a.transport-mode=order"
exit /b %errorlevel%
