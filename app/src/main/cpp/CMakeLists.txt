cmake_minimum_required(VERSION 3.22.1)
project("termux-pty-wrapper")

# forkpty() is in Android's bionic libc — linked automatically.
# No external libraries needed.
add_library(termux-pty-wrapper SHARED termux-pty.c)
target_link_libraries(termux-pty-wrapper log)
target_compile_options(termux-pty-wrapper PRIVATE -Wall -O2)
