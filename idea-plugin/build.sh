#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$ROOT_DIR/.." && pwd)"
IDEA_HOME="${IDEA_HOME:-/Applications/IntelliJ IDEA.app/Contents}"

CLASSES_DIR="$ROOT_DIR/build/classes"
LIBS_DIR="$ROOT_DIR/build/libs"
PACKAGE_DIR="$ROOT_DIR/build/package"
DIST_DIR="$ROOT_DIR/build/distributions"
PLUGIN_NAME="batch-ref-idea-plugin"

rm -rf "$CLASSES_DIR" "$LIBS_DIR" "$PACKAGE_DIR" "$DIST_DIR"
mkdir -p "$CLASSES_DIR" "$LIBS_DIR" "$PACKAGE_DIR/$PLUGIN_NAME/lib" "$DIST_DIR"

CLASSPATH="$(find "$IDEA_HOME/lib" "$IDEA_HOME/plugins/java/lib" -name '*.jar' -print | tr '\n' ':')"

javac --release 17 \
  -cp "$CLASSPATH" \
  -d "$CLASSES_DIR" \
  $(find "$ROOT_DIR/src/main/java" -name '*.java' -print)

cp -R "$ROOT_DIR/src/main/resources/." "$CLASSES_DIR/"
jar cf "$LIBS_DIR/$PLUGIN_NAME.jar" -C "$CLASSES_DIR" .
cp "$LIBS_DIR/$PLUGIN_NAME.jar" "$PACKAGE_DIR/$PLUGIN_NAME/lib/"

(
  cd "$PACKAGE_DIR"
  zip -qr "$DIST_DIR/$PLUGIN_NAME.zip" "$PLUGIN_NAME"
)

echo "$DIST_DIR/$PLUGIN_NAME.zip"
