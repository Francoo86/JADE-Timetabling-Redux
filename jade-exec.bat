@echo off
setlocal

rem Define Java executable path
set JAVA_HOME=C:\Program Files\Java\jdk-21
set JAVA_EXE=%JAVA_HOME%\bin\java.exe

rem Define classpath with all required libraries
set CLASSPATH=out\production\JADE-Timetabling-Redux
set CLASSPATH=%CLASSPATH%;lib\jade\jade.jar
set CLASSPATH=%CLASSPATH%;lib\jade\jadeExamples.jar
set CLASSPATH=%CLASSPATH%;lib\json\json-simple-1.1.1.jar
set CLASSPATH=%CLASSPATH%;lib\json\json-lib-2.4-jdk15.jar
set CLASSPATH=%CLASSPATH%;lib\json\jackson-core-2.18.0.jar
set CLASSPATH=%CLASSPATH%;lib\json\jackson-databind-2.18.0.jar
set CLASSPATH=%CLASSPATH%;lib\json\jackson-annotations-2.18.0.jar
set CLASSPATH=%CLASSPATH%;lib\json\javax.json-api-1.1-javadoc.jar

rem Run the application
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath "%CLASSPATH%" aplicacion.IterativeAplicacion %*

endlocal