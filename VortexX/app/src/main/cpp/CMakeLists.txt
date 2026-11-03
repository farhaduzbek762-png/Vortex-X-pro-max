cmake_minimum_required(VERSION 3.22)
project(vortex_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# کتابخانه استاتیک Rust (خروجی cargo-ndk — ساخته شده در GitHub Actions)
set(RUST_LIB ${CMAKE_CURRENT_SOURCE_DIR}/rust-libs/${ANDROID_ABI}/libvortex_engine.a)
if(NOT EXISTS ${RUST_LIB})
    message(FATAL_ERROR "Rust core not found: ${RUST_LIB} — اول cargo-ndk را اجرا کن!")
endif()

add_library(rust_core STATIC IMPORTED)
set_target_properties(rust_core PROPERTIES IMPORTED_LOCATION ${RUST_LIB})

add_library(vortex_engine SHARED vortex_jni_bridge.cpp)

find_library(log-lib log)
target_link_libraries(vortex_engine
    rust_core
    ${log-lib}
    android
    unwind
)
