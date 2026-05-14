#include <jni.h>

#include <string>
#include <vector>

#include "pty_session.h"

namespace {

jstring err(JNIEnv* env, const char* msg) {
    return env->NewStringUTF(msg);
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_localagent_runtime_PtySession_nativeSpawn(JNIEnv* env, jclass, jobjectArray jArgv, jobjectArray jEnv,
                                                   jstring jCwd, jint rows, jint cols) {
    if (!jArgv) {
        return nullptr;
    }
    const jsize argc = env->GetArrayLength(jArgv);
    if (argc <= 0) {
        return nullptr;
    }

    std::vector<std::string> argv_store(static_cast<size_t>(argc));
    std::vector<char*> argv_ptrs(static_cast<size_t>(argc + 1));
    for (jsize i = 0; i < argc; ++i) {
        auto* js = static_cast<jstring>(env->GetObjectArrayElement(jArgv, i));
        if (!js) {
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "null argv element");
            return nullptr;
        }
        const char* utf = env->GetStringUTFChars(js, nullptr);
        argv_store[static_cast<size_t>(i)].assign(utf ? utf : "");
        env->ReleaseStringUTFChars(js, utf);
        env->DeleteLocalRef(js);
        argv_ptrs[static_cast<size_t>(i)] = argv_store[static_cast<size_t>(i)].data();
    }
    argv_ptrs[static_cast<size_t>(argc)] = nullptr;

    std::vector<std::string> env_store;
    std::vector<char*> env_ptrs;
    if (jEnv) {
        const jsize envc = env->GetArrayLength(jEnv);
        env_store.reserve(static_cast<size_t>(envc));
        env_ptrs.reserve(static_cast<size_t>(envc + 1));
        for (jsize i = 0; i < envc; ++i) {
            auto* js = static_cast<jstring>(env->GetObjectArrayElement(jEnv, i));
            if (!js) {
                continue;
            }
            const char* utf = env->GetStringUTFChars(js, nullptr);
            env_store.emplace_back(utf ? utf : "");
            env->ReleaseStringUTFChars(js, utf);
            env->DeleteLocalRef(js);
            env_ptrs.push_back(env_store.back().data());
        }
        env_ptrs.push_back(nullptr);
    }

    std::string cwd;
    const char* cwd_ptr = nullptr;
    if (jCwd) {
        const char* utf = env->GetStringUTFChars(jCwd, nullptr);
        cwd.assign(utf ? utf : "");
        env->ReleaseStringUTFChars(jCwd, utf);
        cwd_ptr = cwd.c_str();
    }

    PtySpawnArgs args{};
    args.cwd = cwd_ptr;
    args.argv = argv_ptrs.data();
    args.envp = jEnv ? env_ptrs.data() : nullptr;
    args.rows = static_cast<unsigned int>(rows);
    args.cols = static_cast<unsigned int>(cols);

    PtySession session{};
    char errbuf[256]{};
    const int rc = pty_spawn(&args, &session, errbuf, sizeof(errbuf));
    if (rc != 0) {
        env->ThrowNew(env->FindClass("java/io/IOException"), errbuf[0] ? errbuf : "pty_spawn failed");
        return nullptr;
    }

    jlongArray out = env->NewLongArray(2);
    if (!out) {
        return nullptr;
    }
    jlong elems[2]{static_cast<jlong>(session.master_fd), static_cast<jlong>(session.child_pid)};
    env->SetLongArrayRegion(out, 0, 2, elems);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localagent_runtime_PtySession_nativeResize(JNIEnv*, jclass, jint masterFd, jint rows, jint cols) {
    return pty_resize(masterFd, static_cast<unsigned int>(rows), static_cast<unsigned int>(cols));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localagent_runtime_PtySession_nativeKill(JNIEnv*, jclass, jint childPid) {
    return pty_kill_process_group(childPid);
}
