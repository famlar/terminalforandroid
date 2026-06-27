/**
 * Termux PTY JNI wrapper
 *
 * Calls forkpty() via Termux's libtermux.so to create a real
 * pseudo-terminal on Android. Returns a FileDescriptor for the
 * master side, which Kotlin wraps with FileInputStream/FileOutputStream.
 *
 * With NO_TERMUX_LIB (CI/CD): returns NULL from nativeCreatePty,
 * Kotlin catches UnsatisfiedLinkError gracefully — SSH tabs still work.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <termios.h>

#ifndef NO_TERMUX_LIB
#include <pty.h>
#endif

#include <android/log.h>

#define TAG "TermuxPty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jobject JNICALL
Java_com_sshterminal_TermuxPty_nativeCreatePty(
    JNIEnv *env, jclass clazz,
    jstring shellPath, jobjectArray args,
    jint cols, jint rows) {

#ifdef NO_TERMUX_LIB
    /* CI/CD stub — no PTY available */
    LOGE("Termux PTY not available (CI build)");
    return NULL;
#else
    const char *shell = (*env)->GetStringUTFChars(env, shellPath, NULL);
    if (shell == NULL) return NULL;

    /* Build argv: [shell, arg0, arg1, ..., NULL] */
    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
    const char **argv = calloc(argc + 2, sizeof(char *));
    if (argv == NULL) {
        (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        return NULL;
    }
    argv[0] = shell;
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        argv[i + 1] = (*env)->GetStringUTFChars(env, arg, NULL);
    }
    argv[argc + 1] = NULL;

    /* winsize */
    struct winsize ws;
    ws.ws_col = cols > 0 ? cols : 80;
    ws.ws_row = rows > 0 ? rows : 24;
    ws.ws_xpixel = ws.ws_col * 8;
    ws.ws_ypixel = ws.ws_row * 14;

    int masterFd;
    pid_t pid = forkpty(&masterFd, NULL, NULL, &ws);

    if (pid == -1) {
        LOGE("forkpty failed: %s", strerror(errno));
        for (int i = 0; i < argc; i++) {
            (*env)->ReleaseStringUTFChars(env, (jstring)(*env)->GetObjectArrayElement(env, args, i), argv[i + 1]);
        }
        free(argv);
        (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        return NULL;
    }

    if (pid == 0) {
        /* Child: set environment for Termux */
        setenv("HOME", "/data/data/com.termux/files/home", 1);
        setenv("PREFIX", "/data/data/com.termux/files/usr", 1);
        setenv("TERM", "xterm-256color", 1);
        setenv("LANG", "en_US.UTF-8", 1);
        setenv("PATH", "/data/data/com.termux/files/usr/bin", 1);
        setenv("SHELL", shell, 1);

        execvp(shell, (char *const *)argv);
        LOGE("execvp failed: %s", strerror(errno));
        exit(127);
    }

    /* Parent: set master fd non-blocking */
    int flags = fcntl(masterFd, F_GETFL, 0);
    if (flags != -1) {
        fcntl(masterFd, F_SETFL, flags | O_NONBLOCK);
    }

    /* Create java.io.FileDescriptor from the master fd */
    jclass fdClass = (*env)->FindClass(env, "java/io/FileDescriptor");
    jmethodID fdInit = (*env)->GetMethodID(env, fdClass, "<init>", "()V");
    jobject fdObj = (*env)->NewObject(env, fdClass, fdInit);
    jfieldID fdField = (*env)->GetFieldID(env, fdClass, "fd", "I");
    (*env)->SetIntField(env, fdObj, fdField, masterFd);

    for (int i = 0; i < argc; i++) {
        (*env)->ReleaseStringUTFChars(env, (jstring)(*env)->GetObjectArrayElement(env, args, i), argv[i + 1]);
    }
    free(argv);
    (*env)->ReleaseStringUTFChars(env, shellPath, shell);

    return fdObj;
#endif
}

JNIEXPORT void JNICALL
Java_com_sshterminal_TermuxPty_nativeResize(
    JNIEnv *env, jclass clazz, jobject fdObj, jint cols, jint rows) {

#ifdef NO_TERMUX_LIB
    /* no-op */
    (void)fdObj; (void)cols; (void)rows;
#else
    jclass fdClass = (*env)->FindClass(env, "java/io/FileDescriptor");
    jfieldID fdField = (*env)->GetFieldID(env, fdClass, "fd", "I");
    int fd = (*env)->GetIntField(env, fdObj, fdField);

    struct winsize ws;
    ws.ws_col = cols;
    ws.ws_row = rows;
    ws.ws_xpixel = cols * 8;
    ws.ws_ypixel = rows * 14;

    if (ioctl(fd, TIOCSWINSZ, &ws) == -1) {
        LOGE("ioctl TIOCSWINSZ failed: %s", strerror(errno));
    }
#endif
}

JNIEXPORT void JNICALL
Java_com_sshterminal_TermuxPty_nativeClose(
    JNIEnv *env, jclass clazz, jobject fdObj) {

#ifdef NO_TERMUX_LIB
    /* no-op */
    (void)fdObj;
#else
    jclass fdClass = (*env)->FindClass(env, "java/io/FileDescriptor");
    jfieldID fdField = (*env)->GetFieldID(env, fdClass, "fd", "I");
    int fd = (*env)->GetIntField(env, fdObj, fdField);

    if (fd >= 0) {
        close(fd);
        (*env)->SetIntField(env, fdObj, fdField, -1);
    }
#endif
}
