#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PtySpawnArgs {
    const char* cwd;
    const char* const* argv;
    const char* const* envp;
    unsigned int rows;
    unsigned int cols;
} PtySpawnArgs;

typedef struct PtySession {
    int master_fd;
    int slave_fd;
    int child_pid;
} PtySession;

/** Returns 0 on success. On failure writes a short message into errbuf and returns errno or -1. */
int pty_spawn(const PtySpawnArgs* args, PtySession* out, char* errbuf, size_t errlen);

int pty_resize(int master_fd, unsigned int rows, unsigned int cols);

int pty_kill_process_group(int child_pid);

#ifdef __cplusplus
}
#endif
