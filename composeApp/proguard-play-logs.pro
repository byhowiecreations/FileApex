# Play flavor only: informational and debug logging is removed from the release code, so nothing is printed to
# the system log. Warnings and errors (Log.w, Log.e) stay. println is inlined to System.out.println by Kotlin.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}
