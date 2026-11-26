# Phone TV uses no reflection of its own. Media3 ships its own consumer rules.
# Keep line numbers so crash reports from users stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The software audio decoder is found by name (Media3 looks it up when the player is built)
# and its native half calls back into these classes by name too. Renaming either breaks sound.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.decoder.** { native <methods>; }
