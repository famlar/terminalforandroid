/**
 * Android PTY JNI — returns int fd (Kotlin handles FileDescriptor via reflection)
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

JNIEXPORT jint JNICALL
Java_com_sshterminal_TermuxPty_nativeCreatePty(
    JNIEnv *env, jclass clazz,
    jstring shellPath, jobjectArray args,
    jint cols, jint rows) {

#ifndef __ANDROID__
    return -1;
#else
    LOGI("nativeCreatePty start");
    const char *shell = (*env)->GetStringUTFChars(env, shellPath, NULL);
    if (!shell) { LOGE("shellPath null"); return -1; }

    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
    const char **argv = calloc(argc + 2, sizeof(char*));
    if (!argv) { (*env)->ReleaseStringUTFChars(env, shellPath, shell); return -1; }
    argv[0] = shell;
    for (int i = 0; i < argc; i++) {
        jstring a = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        argv[i+1] = (*env)->GetStringUTFChars(env, a, NULL);
    }
    argv[argc+1] = NULL;

    LOGI("opening /dev/ptmx");
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) { LOGE("open /dev/ptmx: %s", strerror(errno)); goto fail; }
    LOGI("ptm=%d", ptm);

    if (grantpt(ptm) < 0 || unlockpt(ptm) < 0) { LOGE("grantpt/unlockpt: %s", strerror(errno)); close(ptm); goto fail; }

    LOGI("about to fork()");
    pid_t pid = fork();
    if (pid < 0) { LOGE("fork: %s", strerror(errno)); close(ptm); goto fail; }

    if (pid == 0) {
        char *name = ptsname(ptm); if (!name) _exit(1);
        if (setsid() < 0) _exit(2);
        int pts = open(name, O_RDWR); if (pts < 0) _exit(3);
        close(ptm);
        dup2(pts, 0); dup2(pts, 1); dup2(pts, 2); close(pts);
        struct winsize ws = {cols, rows, cols*8, rows*14};
        ioctl(0, TIOCSWINSZ, &ws);
        char *envp[] = {"HOME=/data/data/com.termux/files/home",
                        "PREFIX=/data/data/com.termux/files/usr",
                        "TERM=xterm-256color","LANG=en_US.UTF-8",
                        "PATH=/data/data/com.termux/files/usr/bin:/system/bin",NULL};
        execve(shell, (char* const*)argv, envp);
        _exit(127);
    }

    LOGI("fork OK: child pid=%d", (int)pid);
    fcntl(ptm, F_SETFL, fcntl(ptm, F_GETFL, 0) | O_NONBLOCK);
    struct winsize ws = {cols, rows, cols*8, rows*14};
    ioctl(ptm, TIOCSWINSZ, &ws);

    for (int i = 0; i < argc; i++)
        (*env)->ReleaseStringUTFChars(env, (jstring)(*env)->GetObjectArrayElement(env, args, i), argv[i+1]);
    free(argv);
    (*env)->ReleaseStringUTFChars(env, shellPath, shell);
    LOGI("nativeCreatePty OK → fd=%d", ptm);
    return ptm;

fail:
    for (int i = 0; i < argc; i++)
        (*env)->ReleaseStringUTFChars(env, (jstring)(*env)->GetObjectArrayElement(env, args, i), argv[i+1]);
    free(argv);
    (*env)->ReleaseStringUTFChars(env, shellPath, shell);
    LOGE("nativeCreatePty FAILED");
    return -1;
#endif
}

JNIEXPORT void JNICALL
Java_com_sshterminal_TermuxPty_nativeResize(
    JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows) {
#ifdef __ANDROID__
    struct winsize ws = {cols, rows, cols*8, rows*14};
    ioctl(fd, TIOCSWINSZ, &ws);
#else
    (void)fd; (void)cols; (void)rows;
#endif
}

JNIEXPORT void JNICALL
Java_com_sshterminal_TermuxPty_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
#ifdef __ANDROID__
    if (fd >= 0) close(fd);
#else
    (void)fd;
#endif
}
