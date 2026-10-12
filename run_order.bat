@echo off
setlocal
cd /d "%~dp0"
set EASTCOM_ORDER_SIMULATOR_ENABLED=true

call mvn -B -pl samples -am -DskipTests install
if errorlevel 1 exit /b %errorlevel%

rem Offline simulator only: public demo credentials, never the private OMC credentials file.
call mvn -B -f samples\pom.xml spring-boot:run "-Dspring-boot.run.main-class=dev.openan.workflow.engine.examples.demo.SpringSpnDemo" "-Dspring-boot.run.arguments=--a2a.transport-mode=order --a2a.local-workflow-enabled=true --a2a.embedded-omc-enabled=true --a2a.order.simulator-enabled=true --a2a.order.host=127.0.0.1 --a2a.order.port=26401 --a2a.order.username=sim-user --a2a.order.password=sim-password --a2a.order.client-id=sim-client --a2a.order.client-secret=sim-secret --a2a.order.city1-ne=sim-city1 --a2a.order.city2-ne=sim-city2 --a2a.order.simulator-city1-target-url=https://127.0.0.1:26335 --a2a.order.simulator-city2-target-url=https://127.0.0.1:26336 --a2a.order.simulator-city1-username=admin --a2a.order.simulator-city1-password=Admin@123 --a2a.order.simulator-city2-username=admin --a2a.order.simulator-city2-password=Admin@123 --a2a.order.omc-auth-enabled=true --a2a.order.omc-credentials-path=classpath:spn_agent_credentials.example.json"
exit /b %errorlevel%
