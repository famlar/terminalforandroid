cmake_minimum_required(VERSION 3.22.1)
project("termux-pty-wrapper")

# Termux libtermux.so provides forkpty() on Android.
# On a real Termux device it's at $PREFIX/lib/libtermux.so.
# For CI/CD builds without Termux, link fails gracefully.
find_library(TERMUX_LIB termux
    PATHS /data/data/com.termux/files/usr/lib
    NO_DEFAULT_PATH
)

if(NOT TERMUX_LIB)
    message(WARNING "libtermux.so not found — PTY local terminal disabled (SSH only)")
endif()

add_library(termux-pty-wrapper SHARED termux-pty.c)

target_link_libraries(termux-pty-wrapper
    ${TERMUX_LIB}
    log
)

target_compile_options(termux-pty-wrapper PRIVATE -Wall -O2)
