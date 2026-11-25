# AgentForge 沙盒的原生件：Landlock（文件系统加固）+ seccomp（网络隔离）+ 代理桥（LD_PRELOAD）。
# 只出共享库。日常用 Gradle 的 :sandbox:buildNative（直接调 cc）；这份 CMake 留给交叉编译 / 打包。
cmake_minimum_required(VERSION 3.16)
project(agentforge_sandbox_native C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
add_library(agentforge_sandbox_native SHARED src/ks_sandbox.c)
target_compile_options(agentforge_sandbox_native PRIVATE -Wall -Wextra -fvisibility=hidden)
set_target_properties(agentforge_sandbox_native PROPERTIES
  C_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON
  OUTPUT_NAME agentforge_sandbox_native PREFIX "lib")
