/**
 * Android PTY JNI — Termux-compatible manual PTY
 *
 * open(/dev/ptmx) → grantpt → unlockpt → fork → setsid → execve
 * Key: after fork(), child uses ONLY async-signal-safe functions.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include <errno.h>

#define TAG "PTY"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jobject JNICALL
Java_com_sshterminal_TermuxPty_nativeCreatePty(
    JNIEnv *env, jclass clazz,
    jstring shellPath, jobjectArray args,
    jint cols, jint rows) {

#ifndef __ANDROID__
    return NULL;
#else
    LOGI("nativeCreatePty start");

    const char *shell = (*env)->GetStringUTFChars(env, shellPath, NULL);
    if (!shell) { LOGE("shellPath null"); return NULL; }
    LOGI("shell=%s", shell);

    /* Build argv */
    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
    const char **argv = calloc(argc + 2, sizeof(char*));
    if (!argv) { LOGE("calloc failed"); goto release_shell; }
    argv[0] = shell;
    for (int i = 0; i < argc; i++) {
        jstring a = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        argv[i+1] = (*env)->GetStringUTFChars(env, a, NULL);
    }
    argv[argc+1] = NULL;

    /* Open PTY master */
    LOGI("opening /dev/ptmx");
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) { LOGE("open /dev/ptmx: %s (errno=%d)", strerror(errno), errno); goto fail_argv; }
    LOGI("ptm=%d", ptm);

    if (grantpt(ptm) < 0) { LOGE("grantpt: %s", strerror(errno)); goto fail_ptm; }
    if (unlockpt(ptm) < 0) { LOGE("unlockpt: %s", strerror(errno)); goto fail_ptm; }

    LOGI("about to fork()");
    pid_t pid = fork();
    if (pid < 0) { LOGE("fork: %s (errno=%d)", strerror(errno), errno); goto fail_ptm; }

    if (pid == 0) {
        /* ═══════════════ CHILD ═══════════════ */
        /* ASYNC-SIGNAL-SAFE ONLY after fork! No malloc, no setenv, no JNI */
        char ptsName[64];
        char *name = ptsname(ptm);
        if (!name) _exit(1);

        /* Create new session */
        if (setsid() < 0) _exit(2);

        /* Open slave */
        int pts = open(name, O_RDWR);
        if (pts < 0) _exit(3);
        close(ptm);

        /* Redirect stdio to slave */
        dup2(pts, STDIN_FILENO);
        dup2(pts, STDOUT_FILENO);
        dup2(pts, STDERR_FILENO);
        close(pts);

        /* Set terminal size (ioctl is async-signal-safe) */
        struct winsize ws = { (unsigned short)cols, (unsigned short)rows,
                              (unsigned short)(cols*8), (unsigned short)(rows*14) };
        ioctl(STDIN_FILENO, TIOCSWINSZ, &ws);

        /* Build environment (static, no allocation) */
        char *envp[] = {
            "HOME=/data/data/com.termux/files/home",
            "PREFIX=/data/data/com.termux/files/usr",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "PATH=/data/data/com.termux/files/usr/bin:/system/bin",
            NULL
        };

        execve(shell, (char* const*)argv, envp);
        _exit(127);
    }

    /* ═══════════════ PARENT ═══════════════ */
    LOGI("fork OK: child pid=%d", (int)pid);

    /* Set non-blocking */
    int flags = fcntl(ptm, F_GETFL, 0);
    if (flags >= 0) fcntl(ptm, F_SETFL, flags | O_NONBLOCK);

    /* Window size on master */
    struct winsize ws = { (unsigned short)cols, (unsigned short)rows,
                          (unsigned short)(cols*8), (unsigned short)(rows*14) };
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
    LOGI("nativeCreatePty OK");
    return fdObj;

fail_ptm:
    close(ptm);
fail_argv:
    for (int i = 0; i < argc; i++)
        (*env)->ReleaseStringUTFChars(env, (jstring)(*env)->GetObjectArrayElement(env, args, i), argv[i+1]);
    free(argv);
release_shell:
    (*env)->ReleaseStringUTFChars(env, shellPath, shell);
    LOGE("nativeCreatePty FAILED");
    return NULL;
#endif
}

JNIEXPORT void JNICALL
Java_com_sshterminal_TermuxPty_nativeResize(
    JNIEnv *env, jclass clazz, jobject fdObj, jint cols, jint rows) {
#ifdef __ANDROID__
    jclass fc = (*env)->FindClass(env, "java/io/FileDescriptor");
    int fd = (*env)->GetIntField(env, fdObj, (*env)->GetFieldID(env, fc, "fd", "I"));
    struct winsize ws = { (unsigned short)cols, (unsigned short)rows,
                          (unsigned short)(cols*8), (unsigned short)(rows*14) };
    ioctl(fd, TIOCSWINSZ, &ws);
#else
    (void)fdObj; (void)cols; (void)rows;
#endif
}

JNIEXPORT void JNICALL
Java_com_sshterminal_TermuxPty_nativeClose(
    JNIEnv *env, jclass clazz, jobject fdObj) {
#ifdef __ANDROID__
    jclass fc = (*env)->FindClass(env, "java/io/FileDescriptor");
    int fd = (*env)->GetIntField(env, fdObj, (*env)->GetFieldID(env, fc, "fd", "I"));
    if (fd >= 0) { close(fd); (*env)->SetIntField(env, fdObj, (*env)->GetFieldID(env, fc, "fd", "I"), -1); }
#else
    (void)fdObj;
#endif
}
