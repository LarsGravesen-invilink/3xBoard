-keepattributes Signature,InnerClasses,EnclosingMethod

# JSch resolves configured algorithms and authentication classes by name. The
# app also reflects on Session fields and UserAuth constructors. Keep JSch as a
# whole rather than risk breaking key parsing or a negotiated SSH algorithm.
-keep class com.jcraft.jsch.** { *; }

# The app and JSch's BC XDH/Ed25519 adapters reference BouncyCastle crypto
# classes directly. R8 can trace those references; do not hide BC warnings or
# keep the entire provider. Revisit if a JCA provider is registered by name.

# Optional Windows Pageant integration (JNA is not shipped on Android).
-dontwarn com.sun.jna.Memory
-dontwarn com.sun.jna.Pointer
-dontwarn com.sun.jna.platform.win32.BaseTSD$ULONG_PTR
-dontwarn com.sun.jna.platform.win32.Kernel32
-dontwarn com.sun.jna.platform.win32.User32
-dontwarn com.sun.jna.platform.win32.WinBase$SECURITY_ATTRIBUTES
-dontwarn com.sun.jna.platform.win32.WinBase
-dontwarn com.sun.jna.platform.win32.WinDef$HWND
-dontwarn com.sun.jna.platform.win32.WinDef$LPARAM
-dontwarn com.sun.jna.platform.win32.WinDef$LRESULT
-dontwarn com.sun.jna.platform.win32.WinDef$WPARAM
-dontwarn com.sun.jna.platform.win32.WinNT$HANDLE
-dontwarn com.sun.jna.platform.win32.WinUser$COPYDATASTRUCT

# Optional Log4j/SLF4J logger adapters; the app uses its own JSch logger.
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory

# Optional Kerberos/GSSAPI: the app selects password or public-key auth.
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid

# Optional junixsocket integration; the app connects using TCP sockets and
# disables agent forwarding for its interactive shell.
-dontwarn org.newsclub.net.unix.AFUNIXServerSocketChannel
-dontwarn org.newsclub.net.unix.AFUNIXSocketAddress
-dontwarn org.newsclub.net.unix.AFUNIXSocketChannel
