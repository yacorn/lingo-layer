cmake_minimum_required(VERSION 3.22)
project(screen_translate LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_FLAGS_DEBUG "-g -O2")
set(MECAB "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/mecab/mecab/src")
set(SOURCES viterbi tagger utils eval iconv_utils dictionary_rewriter dictionary_generator
    dictionary_compiler context_id connector nbest_generator writer string_buffer param
    tokenizer char_property dictionary feature_index lbfgs learner_tagger learner libmecab)
foreach(NAME IN LISTS SOURCES)
    list(APPEND MECAB_SOURCES "${MECAB}/${NAME}.cpp")
endforeach()
add_library(mecab STATIC ${MECAB_SOURCES})
set_target_properties(mecab PROPERTIES CXX_STANDARD 14)
target_include_directories(mecab PUBLIC "${MECAB}")
target_compile_definitions(mecab PRIVATE DIC_VERSION=102 VERSION="0.996" PACKAGE="mecab" MECAB_DEFAULT_RC="/dev/null"
    MECAB_USE_UTF8_ONLY HAVE_PTHREAD_H HAVE_GCC_ATOMIC_OPS HAVE_TLS_KEYWORD
    HAVE_SYS_TYPES_H HAVE_SYS_STAT_H HAVE_FCNTL_H HAVE_STRING_H HAVE_SYS_MMAN_H
    HAVE_UNISTD_H HAVE_STDINT_H HAVE_DIRENT_H HAVE_STDLIB_H HAVE_CTYPE_H
    HAVE_GETENV HAVE_GETPAGESIZE HAVE_MMAP)
add_library(screen_translate SHARED engine.cpp)
target_link_libraries(screen_translate PRIVATE mecab android log)
