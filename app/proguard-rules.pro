# Release builds are shrunk and optimized by R8. Libraries that need rules (kotlinx.serialization,
# Tink/protobuf, OkHttp, Room, Compose, PostHog) ship their own, so the app only adds these.

# Keep file and line numbers so crash reports from the field can be read with the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
