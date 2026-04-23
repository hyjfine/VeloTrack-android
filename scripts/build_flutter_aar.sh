#!/bin/bash
set -e

# Same flow as qiqixue-reading-maniac-android/scripts/build_flutter_aar.sh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_APP_DIR="$(dirname "$SCRIPT_DIR")"
FLUTTER_MODULE_DIR="$ANDROID_APP_DIR/../VeloTrack-flutter"
OUTPUT_DIR="$ANDROID_APP_DIR/flutter_aar"

echo "=== Building Flutter AAR ==="
echo "Flutter module: $FLUTTER_MODULE_DIR"
echo "Output: $OUTPUT_DIR"

if [[ ! -f "$FLUTTER_MODULE_DIR/pubspec.yaml" ]]; then
  echo "ERROR: Flutter module not found at $FLUTTER_MODULE_DIR"
  exit 1
fi

if ! grep -q "module:" "$FLUTTER_MODULE_DIR/pubspec.yaml" 2>/dev/null; then
  echo "ERROR: pubspec must declare flutter: module: (add-to-app module). See VeloTrack-flutter/pubspec.yaml."
  exit 1
fi

rm -rf "$OUTPUT_DIR"

cd "$FLUTTER_MODULE_DIR"

flutter pub get

# Release AAR only (same flags as reading-maniac script)
flutter build aar \
    --no-debug \
    --no-profile \
    --build-number=1.0 \
    --output-dir="$OUTPUT_DIR"

echo ""
echo "=== AAR Build Complete ==="
echo "Output directory: $OUTPUT_DIR/host/outputs/repo"
echo ""
echo "To use AAR mode, set in VeloTrack-android/gradle.properties:"
echo "  useFlutterSource=false"
echo ""
echo "Maven repo contents:"
find "$OUTPUT_DIR/host/outputs/repo" \( -name "*.aar" -o -name "*.pom" \) 2>/dev/null | sort || true
