// JNI bridge between Kotlin and the embedded Stockfish UCI engine.
//
// Stockfish is built as a library; its UCI loop is normally driven by std::cin /
// std::cout. We embed it by:
//   1. creating two pipes (one for engine stdin, one for engine stdout)
//   2. dup2'ing them onto STDIN_FILENO/STDOUT_FILENO inside a worker thread
//   3. constructing UCIEngine in that thread and calling loop()
//   4. exposing nativeWrite/nativeReadLine to Kotlin so Java threads can drive it
//
// The fd redirect affects the whole process, but the Android app doesn't otherwise
// use stdin/stdout, so this is safe.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <iostream>
#include <string>
#include <thread>
#include <unistd.h>

#include "bitboard.h"
#include "misc.h"
#include "position.h"
#include "tune.h"
#include "uci.h"

#define LOG_TAG "StockfishJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_initialized{false};
std::atomic<bool> g_engine_alive{false};

// We write commands to g_command_write_fd.
// We read engine output from g_response_read_fd.
int g_command_write_fd  = -1;
int g_response_read_fd  = -1;

std::thread g_engine_thread;

void run_engine(int stdin_read_fd, int stdout_write_fd) {
    // Redirect process stdin/stdout to our pipes. This is process-wide, but the
    // Android app doesn't otherwise use them.
    if (dup2(stdin_read_fd,  STDIN_FILENO)  < 0) { LOGE("dup2 STDIN failed"); return; }
    if (dup2(stdout_write_fd, STDOUT_FILENO) < 0) { LOGE("dup2 STDOUT failed"); return; }
    ::close(stdin_read_fd);
    ::close(stdout_write_fd);

    // Force std::cout to flush on every line.
    setvbuf(stdout, nullptr, _IOLBF, 0);
    std::cout.setf(std::ios::unitbuf);

    using namespace Stockfish;
    Bitboards::init();
    Position::init();

    char prog[] = "stockfish";
    char* argv[] = { prog, nullptr };

    auto uci = std::make_unique<UCIEngine>(1, argv);
    Tune::init(uci->engine_options());

    std::cout << engine_info() << std::endl;

    LOGI("UCI loop starting");
    uci->loop();  // blocks until EOF on stdin (we close g_command_write_fd to signal it)
    LOGI("UCI loop exited");

    g_engine_alive.store(false);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_eregruser_blindfoldchess_engine_StockfishJni_nativeStart(JNIEnv* /*env*/, jclass /*cls*/) {
    bool expected = false;
    if (!g_initialized.compare_exchange_strong(expected, true)) {
        LOGW("nativeStart called while already initialized");
        return JNI_TRUE;
    }

    int cmd_pipe[2];   // [0]=engine reads, [1]=we write
    int resp_pipe[2];  // [0]=we read, [1]=engine writes
    if (pipe(cmd_pipe) != 0) {
        LOGE("pipe(cmd_pipe) failed: %s", strerror(errno));
        g_initialized.store(false);
        return JNI_FALSE;
    }
    if (pipe(resp_pipe) != 0) {
        LOGE("pipe(resp_pipe) failed: %s", strerror(errno));
        ::close(cmd_pipe[0]); ::close(cmd_pipe[1]);
        g_initialized.store(false);
        return JNI_FALSE;
    }

    g_command_write_fd = cmd_pipe[1];
    g_response_read_fd = resp_pipe[0];

    int engine_stdin  = cmd_pipe[0];
    int engine_stdout = resp_pipe[1];

    g_engine_alive.store(true);
    g_engine_thread = std::thread(run_engine, engine_stdin, engine_stdout);

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_io_github_eregruser_blindfoldchess_engine_StockfishJni_nativeWrite(JNIEnv* env, jclass /*cls*/, jstring jcommand) {
    if (g_command_write_fd < 0 || jcommand == nullptr) return;
    const char* cmd = env->GetStringUTFChars(jcommand, nullptr);
    if (cmd == nullptr) return;
    size_t len = strlen(cmd);
    ssize_t w1 = write(g_command_write_fd, cmd, len);
    ssize_t w2 = write(g_command_write_fd, "\n", 1);
    if (w1 < 0 || w2 < 0) {
        LOGW("write to engine stdin failed: %s", strerror(errno));
    }
    env->ReleaseStringUTFChars(jcommand, cmd);
}

JNIEXPORT jstring JNICALL
Java_io_github_eregruser_blindfoldchess_engine_StockfishJni_nativeReadLine(JNIEnv* env, jclass /*cls*/) {
    if (g_response_read_fd < 0) return nullptr;

    std::string line;
    char ch;
    while (true) {
        ssize_t n = read(g_response_read_fd, &ch, 1);
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGW("read from engine stdout failed: %s", strerror(errno));
            return nullptr;
        }
        if (n == 0) {
            // EOF — engine has exited. Return any partial line, then null on next call.
            if (line.empty()) return nullptr;
            break;
        }
        if (ch == '\n') break;
        if (ch != '\r') line.push_back(ch);
    }
    return env->NewStringUTF(line.c_str());
}

JNIEXPORT void JNICALL
Java_io_github_eregruser_blindfoldchess_engine_StockfishJni_nativeStop(JNIEnv* /*env*/, jclass /*cls*/) {
    if (g_command_write_fd >= 0) {
        // Best-effort: ask the engine to quit cleanly, then close to signal EOF.
        const char* quit = "quit\n";
        ssize_t w = write(g_command_write_fd, quit, strlen(quit));
        (void)w;
        ::close(g_command_write_fd);
        g_command_write_fd = -1;
    }
    if (g_engine_thread.joinable()) {
        g_engine_thread.join();
    }
    if (g_response_read_fd >= 0) {
        ::close(g_response_read_fd);
        g_response_read_fd = -1;
    }
    g_initialized.store(false);
}

}  // extern "C"
