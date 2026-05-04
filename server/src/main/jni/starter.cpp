#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <fcntl.h>
#include <limits.h>
#include <unistd.h>
#include <sys/types.h>

#define EXIT_FATAL_SET_CLASSPATH 3
#define EXIT_FATAL_FORK 4
#define EXIT_FATAL_APP_PROCESS 5
#define EXIT_FATAL_UID 6
#define EXIT_FATAL_PM_PATH 7

#define PACKAGE_NAME "yangfentuozi.batteryrecorder"
#define SERVER_NAME "batteryrecorder_server"
#define SERVER_CLASS_PATH "yangfentuozi.batteryrecorder.server.daemon.Main"

static char *trim(char *str) {
    if (str == nullptr) return nullptr;

    while (*str != '\0' && (*str == ' ' || *str == '\n' || *str == '\r' || *str == '\t')) {
        ++str;
    }

    size_t len = strlen(str);
    while (len > 0) {
        char c = str[len - 1];
        if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
        str[--len] = '\0';
    }

    return str;
}

static void run_server(const char *dex_path) {
    if (setenv("CLASSPATH", dex_path, 1) != 0) {
        fprintf(stderr, "fatal: can't set CLASSPATH\n");
        exit(EXIT_FATAL_SET_CLASSPATH);
    }

    char class_path_arg[PATH_MAX];
    snprintf(class_path_arg, sizeof(class_path_arg), "-Djava.class.path=%s", dex_path);

    char nice_name_arg[64];
    snprintf(nice_name_arg, sizeof(nice_name_arg), "--nice-name=%s", SERVER_NAME);

    char *const argv[] = {
        const_cast<char *>("/system/bin/app_process"),
        class_path_arg,
        const_cast<char *>("/system/bin"),
        nice_name_arg,
        const_cast<char *>(SERVER_CLASS_PATH),
        nullptr
    };

    execvp(argv[0], argv);
    fprintf(stderr, "fatal: exec app_process failed, errno=%d\n", errno);
    exit(EXIT_FATAL_APP_PROCESS);
}

static void start_server(const char *dex_path) {
    pid_t pid = fork();
    switch (pid) {
        case -1:
            fprintf(stderr, "fatal: can't fork\n");
            exit(EXIT_FATAL_FORK);
        case 0: {
            setsid();
            chdir("/");
            int fd = open("/dev/null", O_RDWR);
            if (fd != -1) {
                dup2(fd, STDIN_FILENO);
                dup2(fd, STDOUT_FILENO);
                dup2(fd, STDERR_FILENO);
                if (fd > 2) close(fd);
            }
            run_server(dex_path);
        }
        default:
            printf("info: batteryrecorder_server pid is %d\n", pid);
            printf("info: batteryrecorder_starter exit with 0\n");
            exit(EXIT_SUCCESS);
    }
}

int main(int argc, char *argv[]) {
    const uid_t uid = getuid();
    if (uid != 0 && uid != 2000) {
        fprintf(stderr, "fatal: run from non root nor adb user (uid=%d)\n", uid);
        exit(EXIT_FATAL_UID);
    }

    const char *apk_path = nullptr;
    for (int i = 1; i < argc; ++i) {
        if (strncmp(argv[i], "--apk=", 6) == 0) {
            apk_path = argv[i] + 6;
            break;
        }
    }

    char resolved_apk_path[PATH_MAX] = {0};
    if (apk_path == nullptr || access(apk_path, R_OK) != 0) {
        FILE *f = popen("pm path " PACKAGE_NAME, "r");
        if (f != nullptr) {
            char line[PATH_MAX] = {0};
            if (fgets(line, sizeof(line), f) != nullptr) {
                char *trimmed = trim(line);
                if (strncmp(trimmed, "package:", 8) == 0) {
                    snprintf(resolved_apk_path, sizeof(resolved_apk_path), "%s", trimmed + 8);
                    apk_path = resolved_apk_path;
                }
            }
            pclose(f);
        }
    }

    if (apk_path == nullptr || access(apk_path, R_OK) != 0) {
        fprintf(stderr, "fatal: can't get readable apk path\n");
        exit(EXIT_FATAL_PM_PATH);
    }

    start_server(apk_path);
}
