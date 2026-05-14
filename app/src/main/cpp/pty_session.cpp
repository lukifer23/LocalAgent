#include "pty_session.h"

#include <cerrno>
#include <cstring>

#include <cstdio>

#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <android/log.h>
#define PTY_LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "LocalAgentPTY", __VA_ARGS__)
#else
#define PTY_LOG(...)
#endif

static void make_raw(int fd) {
    termios t{};
    if (tcgetattr(fd, &t) != 0) {
        return;
    }
    cfmakeraw(&t);
    t.c_cc[VMIN] = 1;
    t.c_cc[VTIME] = 0;
    tcsetattr(fd, TCSANOW, &t);
}

int pty_spawn(const PtySpawnArgs* args, PtySession* out, char* errbuf, size_t errlen) {
    if (!args || !out || !args->argv || !args->argv[0]) {
        if (errbuf && errlen > 0) {
            snprintf(errbuf, errlen, "invalid args");
        }
        return EINVAL;
    }

    std::memset(out, 0, sizeof(*out));

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        if (errbuf && errlen > 0) {
            snprintf(errbuf, errlen, "posix_openpt: %s", strerror(errno));
        }
        return errno;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        const int e = errno;
        if (errbuf && errlen > 0) {
            snprintf(errbuf, errlen, "grant/unlock: %s", strerror(e));
        }
        close(master);
        return e;
    }

    char slave_path[256]{};
    if (ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        const int e = errno;
        if (errbuf && errlen > 0) {
            snprintf(errbuf, errlen, "ptsname_r: %s", strerror(e));
        }
        close(master);
        return e;
    }

    const int slave = open(slave_path, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        const int e = errno;
        if (errbuf && errlen > 0) {
            snprintf(errbuf, errlen, "open slave: %s", strerror(e));
        }
        close(master);
        return e;
    }

    struct winsize ws {};
    ws.ws_row = static_cast<unsigned short>(args->rows);
    ws.ws_col = static_cast<unsigned short>(args->cols);
    if (ioctl(master, TIOCSWINSZ, &ws) != 0) {
        PTY_LOG("TIOCSWINSZ master failed: %s", strerror(errno));
    }

    const pid_t pid = fork();
    if (pid < 0) {
        const int e = errno;
        if (errbuf && errlen > 0) {
            snprintf(errbuf, errlen, "fork: %s", strerror(e));
        }
        close(slave);
        close(master);
        return e;
    }

    if (pid == 0) {
        close(master);
        setsid();
        if (ioctl(slave, TIOCSCTTY, 0) != 0) {
            PTY_LOG("TIOCSCTTY failed: %s", strerror(errno));
        }
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) {
            close(slave);
        }

        make_raw(STDIN_FILENO);

        if (args->cwd && chdir(args->cwd) != 0) {
            PTY_LOG("chdir failed: %s", strerror(errno));
        }

        if (args->envp) {
            size_t count = 0;
            while (args->envp[count]) {
                ++count;
            }
            auto** env_block = static_cast<char**>(malloc(sizeof(char*) * (count + 1)));
            if (!env_block) {
                _exit(126);
            }
            for (size_t i = 0; i < count; ++i) {
                env_block[i] = strdup(args->envp[i]);
            }
            env_block[count] = nullptr;
            environ = env_block;
        }

        execvp(args->argv[0], const_cast<char* const*>(args->argv));
        _exit(127);
    }

    close(slave);
    out->master_fd = master;
    out->slave_fd = -1;
    out->child_pid = static_cast<int>(pid);
    return 0;
}

int pty_resize(int master_fd, unsigned int rows, unsigned int cols) {
    if (master_fd < 0) {
        return EINVAL;
    }
    winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_col = static_cast<unsigned short>(cols);
    if (ioctl(master_fd, TIOCSWINSZ, &ws) != 0) {
        return errno;
    }
    return 0;
}

int pty_kill_process_group(int child_pid) {
    if (child_pid <= 0) {
        return EINVAL;
    }
    const pid_t p = static_cast<pid_t>(child_pid);
    if (kill(-p, SIGTERM) != 0 && errno != ESRCH) {
        return errno;
    }
    timespec ts{};
    ts.tv_sec = 0;
    ts.tv_nsec = 200000000L;
    for (int i = 0; i < 25; ++i) {
        int status = 0;
        const pid_t r = waitpid(p, &status, WNOHANG);
        if (r == p) {
            return 0;
        }
        nanosleep(&ts, nullptr);
    }
    kill(-p, SIGKILL);
    waitpid(p, nullptr, 0);
    return 0;
}
