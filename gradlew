#!/bin/sh
#
# Gradle startup script for UN*X
#

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD=maximum

warn() { echo "$*"; } >&2
die() { echo; echo "$*"; echo; exit 1; } >&2

cygwin=false; msys=false; darwin=false; nonstop=false
case "`uname`" in
  CYGWIN*)  cygwin=true ;;
  Darwin*)  darwin=true ;;
  MSYS*|MINGW*) msys=true ;;
  NONSTOP*) nonstop=true ;;
esac

# Find APP_HOME
app_path=$0
while [ -h "$app_path" ]; do
  ls=`ls -ld "$app_path"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  case $link in
    /*) app_path=$link ;;
    *)  app_path=`dirname "$app_path"`/$link ;;
  esac
done
APP_HOME=`cd "\`dirname \"$app_path\"\`" && pwd -P`

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine Java command
if [ -n "$JAVA_HOME" ]; then
  if [ -x "$JAVA_HOME/jre/sh/java" ]; then
    JAVACMD="$JAVA_HOME/jre/sh/java"
  else
    JAVACMD="$JAVA_HOME/bin/java"
  fi
  if [ ! -x "$JAVACMD" ]; then
    die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
  fi
else
  JAVACMD=java
  command -v java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command found."
fi

# Max file descriptors
if ! "$cygwin" && ! "$darwin" && ! "$nonstop"; then
  case $MAX_FD in
    max*) MAX_FD=`ulimit -H -n` 2>/dev/null || warn "Could not query max file descriptors" ;;
  esac
  case $MAX_FD in
    ''|soft) ;;
    *) ulimit -n "$MAX_FD" 2>/dev/null || warn "Could not set max file descriptors" ;;
  esac
fi

# Use eval+xargs to properly handle quoted JVM opts
set -- \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"

eval "set -- $(
  printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
  xargs -n1 |
  sed 's~[^a-zA-Z0-9/=@_-]~\\&~g;' |
  tr '\n' ' '
) $(printf '%s\n' '"$@"' | sed 's/[^a-zA-Z0-9/=@_-]/\\&/g')"

exec "$JAVACMD" "$@"
