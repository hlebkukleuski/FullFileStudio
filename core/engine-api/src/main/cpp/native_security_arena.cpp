/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org>.
 */

#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <pthread.h>
#include <signal.h> // NOLINT(modernize-deprecated-headers)
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <cstddef>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#include <fcntl.h>
#include <android/log.h>
#include <algorithm>
#include <csetjmp>
#include <syscall.h>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <mutex>

#define LOG_TAG "NativeSecurityArena"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::once_flag g_signal_once_flag;
static pthread_key_t g_altstack_key;

static thread_local sigjmp_buf g_jump_env;
static thread_local volatile sig_atomic_t g_jump_active = 0;
static thread_local bool g_thread_altstack_installed = false;

static struct sigaction old_sigsegv;
static struct sigaction old_sigbus;

static void FreeThreadAltStack(void* stack_mem) {
    if (!stack_mem) return;
    stack_t ss;
    std::memset(&ss, 0, sizeof(ss));
    ss.ss_flags = SS_DISABLE;
    sigaltstack(&ss, nullptr);
    free(stack_mem);
}

static int GetFdCompat(JNIEnv* env, jobject fdObj) {
    if (!fdObj) return -1;
    jclass fdClass = env->GetObjectClass(fdObj);
    if (!fdClass) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -1;
    }
    jfieldID fdField = env->GetFieldID(fdClass, "descriptor", "I");
    if (env->ExceptionCheck() || !fdField) {
        env->ExceptionClear();
        env->DeleteLocalRef(fdClass);
        return -1;
    }
    int fd = env->GetIntField(fdObj, fdField);
    env->DeleteLocalRef(fdClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return fd;
}

void SafeSignalGuard(int sig, siginfo_t *info, void *context) {
    if (g_jump_active && (sig == SIGBUS || sig == SIGSEGV)) {
        g_jump_active = 0;
        siglongjmp(g_jump_env, 1);
    }

    struct sigaction* old_sa = (sig == SIGSEGV) ? &old_sigsegv : &old_sigbus;
    if ((old_sa->sa_flags & SA_SIGINFO) && old_sa->sa_sigaction && old_sa->sa_sigaction != SafeSignalGuard) {
        old_sa->sa_sigaction(sig, info, context);
    } else if (old_sa->sa_handler && old_sa->sa_handler != SIG_DFL && old_sa->sa_handler != SIG_IGN) {
        old_sa->sa_handler(sig);
    } else {
        signal(sig, SIG_DFL);
        raise(sig);
    }
}

static void InitSignalHandlersOnce() {
    pthread_key_create(&g_altstack_key, FreeThreadAltStack);

    struct sigaction sa;
    std::memset(&sa, 0, sizeof(sa));
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_NODEFER;
    sa.sa_sigaction = SafeSignalGuard;
    sigaction(SIGSEGV, &sa, &old_sigsegv);
    sigaction(SIGBUS, &sa, &old_sigbus);
}

static void EnsureThreadAltStack() {
    if (g_thread_altstack_installed) return;

    const size_t alt_size = std::max<size_t>(SIGSTKSZ, 64 * 1024);
    void* stack_mem = malloc(alt_size);
    if (!stack_mem) return;

    stack_t ss;
    ss.ss_sp = stack_mem;
    ss.ss_size = alt_size;
    ss.ss_flags = 0;
    if (sigaltstack(&ss, nullptr) == 0) {
        g_thread_altstack_installed = true;
        pthread_setspecific(g_altstack_key, stack_mem);
    } else {
        free(stack_mem);
    }
}

#if defined(__aarch64__)
#define FFS_AUDIT_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define FFS_AUDIT_ARCH AUDIT_ARCH_X86_64
#endif

#define FFS_DENY(nr) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (nr), 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)

static std::mutex g_seccomp_mutex;
static bool g_seccomp_installed = false;

static bool VerifySeccompDenyList() {
    errno = 0;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd >= 0) {
        close(fd);
        return false;
    }
    return errno == EPERM;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnireader_core_engine_SecurityArena_setupSeccompBPF(JNIEnv*, jobject) {
#if defined(FFS_AUDIT_ARCH)
    std::lock_guard<std::mutex> lock(g_seccomp_mutex);
    if (g_seccomp_installed) return VerifySeccompDenyList() ? JNI_TRUE : JNI_FALSE;

    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, FFS_AUDIT_ARCH, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
#if defined(__x86_64__)
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        FFS_DENY(__NR_ptrace),
        FFS_DENY(__NR_process_vm_readv),
        FFS_DENY(__NR_process_vm_writev),
        FFS_DENY(__NR_execve),
        FFS_DENY(__NR_execveat),
        FFS_DENY(__NR_bpf),
        FFS_DENY(__NR_perf_event_open),
        FFS_DENY(__NR_keyctl),
        FFS_DENY(__NR_add_key),
        FFS_DENY(__NR_request_key),
        FFS_DENY(__NR_mount),
        FFS_DENY(__NR_umount2),
        FFS_DENY(__NR_pivot_root),
        FFS_DENY(__NR_init_module),
        FFS_DENY(__NR_finit_module),
        FFS_DENY(__NR_delete_module),
        FFS_DENY(__NR_kexec_load),
#if defined(__NR_kexec_file_load)
        FFS_DENY(__NR_kexec_file_load),
#endif
        FFS_DENY(__NR_swapon),
        FFS_DENY(__NR_swapoff),
        FFS_DENY(__NR_reboot),
        FFS_DENY(__NR_setns),
        FFS_DENY(__NR_unshare),
        FFS_DENY(__NR_acct),
#if defined(__NR_io_uring_setup)
        FFS_DENY(__NR_io_uring_setup),
        FFS_DENY(__NR_io_uring_enter),
        FFS_DENY(__NR_io_uring_register),
#endif
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = {
        static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
        filter,
    };

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        LOGE("seccomp: PR_SET_NO_NEW_PRIVS failed: %d", errno);
        return JNI_FALSE;
    }
    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog) != 0) {
        LOGE("seccomp: filter install failed: %d", errno);
        return JNI_FALSE;
    }
    g_seccomp_installed = true;
    bool verified = VerifySeccompDenyList();
    LOGI("seccomp: deny-list installed, socket(AF_INET) blocked=%d", verified ? 1 : 0);
    return verified ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

static void WipeBufferNeon(void* buffer, size_t size) {
    if (!buffer || size == 0) return;
#if defined(__aarch64__)
    uint8_t* ptr = static_cast<uint8_t*>(buffer);
    size_t chunks = size / 64;
    uint8x16_t zero = vdupq_n_u8(0);
    for (size_t i = 0; i < chunks; ++i) {
        vst1q_u8(ptr, zero);
        vst1q_u8(ptr + 16, zero);
        vst1q_u8(ptr + 32, zero);
        vst1q_u8(ptr + 48, zero);
        ptr += 64;
    }
    size_t rem = size % 64;
    if (rem > 0) {
        std::memset(ptr, 0, rem);
    }
#else
    std::memset(buffer, 0, size);
#endif
    asm volatile("" : : "r"(buffer) : "memory");
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnireader_core_engine_SecurityArena_fadviseDontNeedNative(JNIEnv* env, jobject, jobject fdObj, jlong offset, jlong len) {
    if (!fdObj || offset < 0 || len <= 0) return;
    int fd = GetFdCompat(env, fdObj);
    if (fd >= 0) {
        posix_fadvise(fd, offset, len, POSIX_FADV_DONTNEED);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_omnireader_core_engine_SecurityArena_findSequenceNeonNative(JNIEnv* env, jobject, jobject fdObj, jlong startOffset, jlong size, jbyteArray sequence) {
    if (!fdObj || !sequence || size <= 0 || startOffset < 0 || startOffset >= size) return -1;

    int fd = GetFdCompat(env, fdObj);
    if (fd < 0) return -1;

    jsize seq_len = env->GetArrayLength(sequence);
    if (seq_len <= 0 || seq_len > 4096) return -1;

    uint8_t seq_data[4096];
    env->GetByteArrayRegion(sequence, 0, seq_len, reinterpret_cast<jbyte*>(seq_data));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }

    std::call_once(g_signal_once_flag, InitSignalHandlersOnce);
    EnsureThreadAltStack();

    const size_t CHUNK_SIZE = 256 * 1024;
    void* temp_mmap = mmap(nullptr, CHUNK_SIZE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (temp_mmap == MAP_FAILED) {
        return -1;
    }
    uint8_t* const buffer = static_cast<uint8_t*>(temp_mmap);

    g_jump_active = 1;
    if (sigsetjmp(g_jump_env, 1) != 0) {
        g_jump_active = 0;
        WipeBufferNeon(temp_mmap, CHUNK_SIZE);
        munmap(temp_mmap, CHUNK_SIZE);
        return -1;
    }

    jlong current_offset = startOffset;
    const uint8_t target = seq_data[0];
#if defined(__aarch64__)
    const uint8x16_t seq_vec = vdupq_n_u8(target);
#endif
    jlong found_offset = -1;

    while (current_offset < size) {
        size_t to_read = static_cast<size_t>(std::min<jlong>(static_cast<jlong>(CHUNK_SIZE), size - current_offset));
        if (to_read < static_cast<size_t>(seq_len)) break;

        ssize_t bytes_read;
        do {
            bytes_read = pread(fd, buffer, to_read, current_offset);
        } while (bytes_read == -1 && errno == EINTR);

        if (bytes_read < seq_len) break;

        ssize_t limit = bytes_read - seq_len;
        ssize_t i = 0;

#if defined(__aarch64__)
        for (; i + 15 <= limit; i += 16) {
            uint8x16_t data_vec = vld1q_u8(&buffer[i]);
            uint8x16_t cmp_res = vceqq_u8(data_vec, seq_vec);

            uint64x2_t res_64 = vreinterpretq_u64_u8(cmp_res);
            if (vgetq_lane_u64(res_64, 0) != 0 || vgetq_lane_u64(res_64, 1) != 0) {
                for (int j = 0; j < 16 && (i + j) <= limit; ++j) {
                    if (buffer[i + j] == target && std::memcmp(&buffer[i + j], seq_data, seq_len) == 0) {
                        found_offset = current_offset + i + j;
                        break;
                    }
                }
                if (found_offset >= 0) break;
            }
        }
#endif

        if (found_offset < 0) {
            for (; i <= limit; ++i) {
                if (buffer[i] == target && std::memcmp(&buffer[i], seq_data, seq_len) == 0) {
                    found_offset = current_offset + i;
                    break;
                }
            }
        }

        posix_fadvise(fd, current_offset, bytes_read, POSIX_FADV_DONTNEED);

        if (found_offset >= 0 || current_offset + bytes_read >= size) break;
        current_offset += (bytes_read - seq_len + 1);
    }

    g_jump_active = 0;
    WipeBufferNeon(temp_mmap, CHUNK_SIZE);
    munmap(temp_mmap, CHUNK_SIZE);
    return found_offset;
}