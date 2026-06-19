#!/bin/sh

# Gradle wrapper script

# Determine the project base dir
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"

CLASSPATH=$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi

# Execute the Gradle wrapper main class
exec "$JAVACMD" -Xmx64m -Xms64m \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
