# Keep MediaSessionCompat callback methods reachable.
-keepclassmembers class * extends android.support.v4.media.session.MediaSessionCompat$Callback {
    public *;
}
