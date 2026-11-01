#!/usr/bin/env sh

APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "${JAVA_HOME:-}" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1 && [ ! -x "$JAVACMD" ]; then
    echo "Java was not found. Install JDK 17 and set JAVA_HOME." >&2
    exit 1
fi

exec "$JAVACMD" -Xmx64m -Xms64m ${JAVA_OPTS:-} ${GRADLE_OPTS:-} \
    "-Dorg.gradle.appname=gradlew" \
    "-Dorg.gradle.wrapper.projectDir=$APP_HOME" \
    -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
