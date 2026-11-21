# R8 rules for Video Live Wallpaper.
#
# Nothing in the app is reached by reflection: the activity, the wallpaper
# service and the PreviewFrame view are referenced from XML, and AAPT
# generates keep rules for those automatically. Media3, Material and
# AndroidX ship their own consumer rules.
#
# Keep line numbers so stack traces from release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
