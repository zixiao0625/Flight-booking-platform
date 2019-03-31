SETLOCAL
echo off

if "%3"=="" goto errors

if exist %2 (
	del /S %2
) else (
	mkdir %2
)

echo compiling from: %~f1
javac -cp lib\junit-4.12.jar;lib\hamcrest-core-1.3.jar;.\lib\sqljdbc4.jar;%2 -d %2 %1\*.java

cd %2
jar -cvf out.jar *
cd ..

java -Dfolder=%3 -cp lib\junit-4.12.jar;lib\hamcrest-core-1.3.jar;.\lib\sqljdbc4.jar;%2\out.jar org.junit.runner.JUnitCore Grader
goto end

:errors
echo Usage: runTests.sh ^<source folder^> ^<output folder^> ^<folder name containing test cases^>
echo Compiles java files in ^<source folder^> and put the class files in ^<output folder^>
echo WARNING: output folder is initially deleted and recreated!

:end
