cmake_minimum_required(VERSION 3.22.1)
project("termux-pty-wrapper")

# Find Termux's libtermux.so (provides forkpty on Android/Termux)
find_library(TERMUX_LIB termux
    PATHS /data/data/com.termux/files/usr/lib
    NO_DEFAULT_PATH
)

add_library(termux-pty-wrapper SHARED termux-pty.c)

if(TERMUX_LIB)
    message(STATUS "libtermux.so found — PTY local terminal enabled")
    target_link_libraries(termux-pty-wrapper ${TERMUX_LIB} log)
else()
    message(WARNING "libtermux.so not found — PTY disabled, SSH only")
    target_compile_definitions(termux-pty-wrapper PRIVATE NO_TERMUX_LIB=1)
    target_link_libraries(termux-pty-wrapper log)
endif()

target_compile_options(termux-pty-wrapper PRIVATE -Wall -O2)
