/**
 * Android PTY JNI wrapper — Termux-compatible manual PTY creation
 *
 * Uses open("/dev/ptmx") + grantpt + unlockpt + fork + setsid
 * This is the same approach Termux uses, avoiding forkpty() quirks.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <android/log.h>
#include <errno.h>
#include <signal.h>

#define TAG "TermuxPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jobject JNICALL
Java_com_sshterminal_TermuxPty_nativeCreatePty(
    JNIEnv *env, jclass clazz,
    jstring shellPath, jobjectArray args,
    jint cols, jint rows) {

#ifndef __ANDROID__
    LOGE("PTY not available (CI)");
    return NULL;
#else
    const char *shell = (*env)->GetStringUTFChars(env, shellPath, NULL);
    if (!shell) return NULL;

    /* Build argv */
    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
    const char **argv = calloc(argc + 2, sizeof(char*));
    if (!argv) { (*env)->ReleaseStringUTFChars(env, shellPath, shell); return NULL; }
    argv[0] = shell;
    for (int i = 0; i < argc; i++) {
        jstring a = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        argv[i+1] = (*env)->GetStringUTFChars(env, a, NULL);
    }
    argv[argc+1] = NULL;

    /* ── Manual PTY creation (Termux-style) ── */
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        LOGE("open /dev/ptmx failed: %s", strerror(errno));
        goto fail_open;
    }

    if (grantpt(ptm) < 0) {
        LOGE("grantpt failed: %s", strerror(errno));
        goto fail_pty;
    }

    if (unlockpt(ptm) < 0) {
        LOGE("unlockpt failed: %s", strerror(errno));
        goto fail_pty;
    }

    /* Fork child process */
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        goto fail_pty;
    }

    if (pid == 0) {
        /* ── Child process ── */
        int pts;
        char ptsName[64];

        /* Create new session, become process group leader */
        setsid();

        /* Open slave side */
        pts = open(ptsname(ptm), O_RDWR);
        if (pts < 0) {
            LOGE("child: open pts failed: %s", strerror(errno));
            exit(1);
        }
        close(ptm); /* close master in child */

        /* Redirect stdin/stdout/stderr to slave */
        dup2(pts, STDIN_FILENO);
        dup2(pts, STDOUT_FILENO);
        dup2(pts, STDERR_FILENO);
        close(pts);

        /* Set environment (Termux paths) */
        setenv("HOME", "/data/data/com.termux/files/home", 1);
        setenv("PREFIX", "/data/data/com.termux/files/usr", 1);
        setenv("TERM", "xterm-256color", 1);
        setenv("LANG", "en_US.UTF-8", 1);
        setenv("PATH", "/data/data/com.termux/files/usr/bin:/system/bin", 1);
        setenv("SHELL", shell, 1);

        /* Set terminal size */
        struct winsize ws;
        ws.ws_col = cols > 0 ? cols : 80;
        ws.ws_row = rows > 0 ? rows : 24;
        ws.ws_xpixel = ws.ws_col * 8;
        ws.ws_ypixel = ws.ws_row * 14;
        ioctl(STDIN_FILENO, TIOCSWINSZ, &ws);

        execvp(shell, (char* const*)argv);
        LOGE("execvp failed: %s", strerror(errno));
        exit(127);
    }

    /* ── Parent process ── */
    /* Set non-blocking */
    int flags = fcntl(ptm, F_GETFL, 0);
    if (flags >= 0) fcntl(ptm, F_SETFL, flags | O_NONBLOCK);

    /* Set window size on master */
    struct winsize ws;
    ws.ws_col = cols > 0 ? cols : 80;
    ws.ws_row = rows > 0 ? rows : 24;
    ws.ws_xpixel = ws.ws_col * 8;
    ws.ws_ypixel = ws.ws_row * 14;
    ioctl(ptm, TIOCSWINSZ, &ws);

    /* Create FileDescriptor */
    jclass fdClass = (*env)->FindClass(env, "java/io/FileDescriptor");
    jmethodID fdInit = (*env)->GetMethodID(env, fdClass, "<init>", "()V");
    jobject fdObj = (*env)->NewObject(env, fdClass, fdInit);
    jfieldID fdField = (*env)->GetFieldID(env, fdClass, "fd", "I");
    (*env)->SetIntField(env, fdObj, fdField, ptm);

    /* Cleanup */
    for (int i = 0; i < argc; i++)
        (*env)->ReleaseStringUTFChars(env, (jstring)(*env)->GetObjectArrayElement(env, args, i), argv[i+1]);
    free(argv);
    (*env)->ReleaseStringUTFChars(env, shellPath, shell);
    return fdObj;

fail_pty:
    close(ptm);
fail_open:
    for (int i = 0; i < argc; i++)
        (*env)->ReleaseStringUTFChars(env, (jstring)(*env)->GetObjectArrayElement(env, args, i), argv[i+1]);
    free(argv);
    (*env)->ReleaseStringUTFChars(env, shellPath, shell);
    return NULL;
#endif
}

JNIEXPORT void JNICALL
Java_com_sshterminal_TermuxPty_nativeResize(
    JNIEnv *env, jclass clazz, jobject fdObj, jint cols, jint rows) {
#ifdef __ANDROID__
    jclass fdC = (*env)->FindClass(env, "java/io/FileDescriptor");
    int fd = (*env)->GetIntField(env, fdObj, (*env)->GetFieldID(env, fdC, "fd", "I"));
    struct winsize ws = { (unsigned short)cols, (unsigned short)rows, (unsigned short)(cols*8), (unsigned short)(rows*14) };
    if (ioctl(fd, TIOCSWINSZ, &ws) < 0)
        LOGE("TIOCSWINSZ failed: %s", strerror(errno));
#else
    (void)fdObj; (void)cols; (void)rows;
#endif
}

JNIEXPORT void JNICALL
Java_com_sshterminal_TermuxPty_nativeClose(
    JNIEnv *env, jclass clazz, jobject fdObj) {
#ifdef __ANDROID__
    jclass fdC = (*env)->FindClass(env, "java/io/FileDescriptor");
    int fd = (*env)->GetIntField(env, fdObj, (*env)->GetFieldID(env, fdC, "fd", "I"));
    if (fd >= 0) { close(fd); (*env)->SetIntField(env, fdObj, (*env)->GetFieldID(env, fdC, "fd", "I"), -1); }
#else
    (void)fdObj;
#endif
}
