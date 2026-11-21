#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <spawn.h>
#include <unistd.h>

#define KGSL "/sys/class/kgsl/kgsl-3d0/"
#define GPU_SYSFS "/sys/kernel/gpu/"
#define CPUFREQ "/sys/devices/system/cpu/cpufreq/"
#define THERMAL "/sys/class/thermal/"

struct task {
    long pid, tgid, ppid, rss, threads, nice;
    unsigned long uid, flags;
    unsigned long long ticks, start;
    char state;
    char name[512];
};

static size_t read_file(const char *path, char *out, size_t size) {
    FILE *file = fopen(path, "re");
    if (!file) { *out = 0; return 0; }
    size_t count = fread(out, 1, size - 1, file);
    fclose(file);
    out[count] = 0;
    return count;
}

static long node(const char *path) {
    char value[128];
    if (!read_file(path, value, sizeof value)) return -1;
    char *end;
    long result = strtol(value, &end, 10);
    return end == value ? -1 : result;
}

static void sanitize(char *name) {
    for (; *name; ++name) {
        unsigned char ch = (unsigned char)*name;
        if (ch < 32 || ch == 127) *name = ' ';
    }
}

static bool read_task(long pid, struct task *task) {
    char path[128], line[4096];
    snprintf(path, sizeof path, "/proc/%ld/stat", pid);
    if (!read_file(path, line, sizeof line)) return false;
    char *left = strchr(line, '('), *right = strrchr(line, ')');
    if (!left || !right || left >= right || right[1] != ' ') return false;
    *right = 0;
    snprintf(task->name, sizeof task->name, "%s", left + 1);
    task->pid = pid;
    long pages = 0;
    unsigned long long utime = 0, stime = 0;
    char *save = NULL;
    char *field = strtok_r(right + 2, " ", &save);
    for (int i = 3; i <= 24; i++) {
        if (!field) return false;
        switch (i) {
            case 3: task->state = *field; break;
            case 4: task->ppid = strtol(field, NULL, 10); break;
            case 9: task->flags = strtoul(field, NULL, 10); break;
            case 14: utime = strtoull(field, NULL, 10); break;
            case 15: stime = strtoull(field, NULL, 10); break;
            case 19: task->nice = strtol(field, NULL, 10); break;
            case 20: task->threads = strtol(field, NULL, 10); break;
            case 22: task->start = strtoull(field, NULL, 10); break;
            case 24: pages = strtol(field, NULL, 10); break;
        }
        field = strtok_r(NULL, " ", &save);
    }
    task->ticks = utime + stime;
    task->rss = pages * (sysconf(_SC_PAGESIZE) / 1024);
    snprintf(path, sizeof path, "/proc/%ld/status", pid);
    if (!read_file(path, line, sizeof line)) return false;
    char *uid = strstr(line, "\nUid:");
    char *tgid = strstr(line, "\nTgid:");
    if (!uid || sscanf(uid, "\nUid: %lu", &task->uid) != 1
        || !tgid || sscanf(tgid, "\nTgid: %ld", &task->tgid) != 1) return false;

    snprintf(path, sizeof path, "/proc/%ld/cmdline", pid);
    if (read_file(path, line, sizeof line) && line[0]) {
        snprintf(task->name, sizeof task->name, "%.*s", (int)sizeof task->name - 1, line);
    }
    sanitize(task->name);
    return true;
}

static void cpu_stats(void) {
    FILE *file = fopen("/proc/stat", "re");
    if (!file) return;
    char line[512];
    while (fgets(line, sizeof line, file) && !strncmp(line, "cpu", 3)) {
        char name[32];
        unsigned long long t[8] = {0}, total = 0;
        if (sscanf(line, "%31s %llu %llu %llu %llu %llu %llu %llu %llu",
                   name, &t[0], &t[1], &t[2], &t[3], &t[4], &t[5], &t[6], &t[7]) < 5) continue;
        for (size_t i = 0; i < 8; ++i) total += t[i];
        printf("C\t%s\t%llu\t%llu\n", name, total, t[3] + t[4]);
    }
    fclose(file);
}

static void memory_stats(void) {
    char line[4096];
    if (!read_file("/proc/meminfo", line, sizeof line)) return;
    const char *fields[] = {"MemTotal:", "MemAvailable:", "SwapTotal:", "SwapFree:"};
    printf("M");
    for (size_t i = 0; i < sizeof fields / sizeof fields[0]; ++i) {
        char *field = strstr(line, fields[i]);
        printf("\t%ld", field ? strtol(field + strlen(fields[i]), NULL, 10) : -1);
    }
    putchar('\n');
}

static bool numbered_entry(const char *name, const char *prefix) {
    size_t length = strlen(prefix);
    if (strncmp(name, prefix, length) || !name[length]) return false;
    return name[length + strspn(name + length, "0123456789")] == 0;
}

static void thermal_stats(void) {
    DIR *zones = opendir(THERMAL);
    if (!zones) return;
    struct dirent *entry;
    while ((entry = readdir(zones))) {
        if (!numbered_entry(entry->d_name, "thermal_zone")) continue;
        char path[384], type[128];
        snprintf(path, sizeof path, THERMAL "%s/type", entry->d_name);
        if (!read_file(path, type, sizeof type)) continue;
        type[strcspn(type, "\r\n")] = 0;
        sanitize(type);
        if (!*type || strcasestr(type, "bcl") || strcasestr(type, "ibat") || strcasestr(type, "vbat")
            || strcasestr(type, "hw-trip") || !strcasecmp(type, "socd")) continue;
        snprintf(path, sizeof path, THERMAL "%s/temp", entry->d_name);
        long temp = node(path);
        if (temp != -1 && temp >= -40000 && temp <= 180000) {
            printf("T\t%s\t%s\t%ld\n", entry->d_name + strlen("thermal_zone"), type, temp);
        }
    }
    closedir(zones);
}

static void cooling_stats(void) {
    DIR *devices = opendir(THERMAL);
    if (!devices) return;
    struct dirent *entry;
    while ((entry = readdir(devices))) {
        if (!numbered_entry(entry->d_name, "cooling_device")) continue;
        char path[384], type[128];
        snprintf(path, sizeof path, THERMAL "%s/type", entry->d_name);
        if (!read_file(path, type, sizeof type)) continue;
        type[strcspn(type, "\r\n")] = 0;
        sanitize(type);
        if (!strcasestr(type, "cpu") && !strcasestr(type, "gpu") && !strcasestr(type, "kgsl")
                && !strcasestr(type, "mali") && !strcasestr(type, "g3d")) continue;
        snprintf(path, sizeof path, THERMAL "%s/cur_state", entry->d_name);
        long state = node(path);
        if (state > 0) printf("L\t%s\t%ld\n", type, state);
    }
    closedir(devices);
}

static void frequency_stats(void) {
    DIR *policies = opendir(CPUFREQ);
    if (!policies) return;
    struct dirent *entry;
    while ((entry = readdir(policies))) {
        if (!numbered_entry(entry->d_name, "policy")) continue;
        char path[384], cores[128];
        snprintf(path, sizeof path, CPUFREQ "%s/related_cpus", entry->d_name);
        if (!read_file(path, cores, sizeof cores)) {
            snprintf(path, sizeof path, CPUFREQ "%s/affected_cpus", entry->d_name);
            read_file(path, cores, sizeof cores);
        }
        cores[strcspn(cores, "\r\n")] = 0;
        sanitize(cores);
        snprintf(path, sizeof path, CPUFREQ "%s/cpuinfo_cur_freq", entry->d_name);
        long current = node(path);
        bool hardware = current > 0;
        if (!hardware) {
            snprintf(path, sizeof path, CPUFREQ "%s/scaling_cur_freq", entry->d_name);
            current = node(path);
        }
        snprintf(path, sizeof path, CPUFREQ "%s/scaling_max_freq", entry->d_name);
        long limit = node(path);
        snprintf(path, sizeof path, CPUFREQ "%s/cpuinfo_max_freq", entry->d_name);
        long maximum = node(path);
        printf("F\t%s\t%s\t%ld\t%ld\t%ld\t%d\n", entry->d_name, cores, current, limit, maximum, hardware);
    }
    closedir(policies);
}

static long maximum_frequency(const char *path) {
    char values[1024], *save = NULL;
    long maximum = -1;
    read_file(path, values, sizeof values);
    for (char *p = strtok_r(values, " \r\n\t", &save); p; p = strtok_r(NULL, " \r\n\t", &save)) {
        char *end;
        long value = strtol(p, &end, 10);
        if (!*end && value > maximum) maximum = value;
    }
    return maximum;
}

static long gpu_percent(const char *path) {
    char text[128], *end;
    if (!read_file(path, text, sizeof text)) return -1;
    long value = strtol(text, &end, 10);
    if (end == text || value < 0 || value > 100) return -1;
    end += strspn(end, " \t");
    if (*end == '%') ++end;
    return end[strspn(end, " \t\r\n")] ? -1 : value;
}

static long mhz_to_hz(long mhz) {
    return mhz > 0 && mhz <= 10000 ? mhz * 1000000L : -1;
}

static long kgsl_gpu_load(void) {
    long load = gpu_percent(KGSL "gpu_busy_percentage");
    if (load >= 0) return load;
    char text[128], extra;
    unsigned long long busy, total;
    if (read_file(KGSL "gpubusy", text, sizeof text)
            && sscanf(text, "%llu %llu %c", &busy, &total, &extra) == 2 && total > 0 && busy <= total) {
        return (long)(100.0 * (double)busy / (double)total + 0.5);
    }
    return -1;
}

static bool mali_device_stats(const char *device, long *load, long *clock, long *limit, long *maximum) {
    char path[512];
    bool found = false;
    if (*load < 0) {
        snprintf(path, sizeof path, "%s/utilization", device);
        *load = gpu_percent(path);
        if (*load < 0) {
            snprintf(path, sizeof path, "%s/utilisation", device);
            *load = gpu_percent(path);
        }
        found = *load >= 0;
    }
    if (*clock <= 0) {
        snprintf(path, sizeof path, "%s/clock", device);
        *clock = mhz_to_hz(node(path));
        found |= *clock > 0;
    }
    if (found && *limit <= 0) {
        snprintf(path, sizeof path, "%s/dvfs_max_lock", device);
        *limit = mhz_to_hz(node(path));
    }
    if (found && *maximum <= 0) {
        snprintf(path, sizeof path, "%s/dvfs_table", device);
        *maximum = mhz_to_hz(maximum_frequency(path));
    }
    return found;
}

static bool mali_gpu_stats(long *load, long *clock, long *limit, long *maximum) {
    if (mali_device_stats("/sys/class/misc/mali0/device", load, clock, limit, maximum)) return true;
    const char *base = "/sys/bus/platform/devices/";
    DIR *devices = opendir(base);
    if (!devices) return false;
    bool found = false;
    struct dirent *entry;
    while ((entry = readdir(devices))) {
        if (strcasecmp(entry->d_name, "mali") && !strcasestr(entry->d_name, ".mali")) continue;
        char path[384];
        snprintf(path, sizeof path, "%s%s", base, entry->d_name);
        found = mali_device_stats(path, load, clock, limit, maximum);
        if (found) break;
    }
    closedir(devices);
    return found;
}

static long devfreq_gpu_clock(long *limit, long *maximum) {
    const char *base = "/sys/class/devfreq/";
    DIR *devices = opendir(base);
    if (!devices) return -1;
    struct dirent *entry;
    long result = -1;
    while ((entry = readdir(devices))) {
        if (!strcasestr(entry->d_name, "gpu") && !strcasestr(entry->d_name, "kgsl")
                && !strcasestr(entry->d_name, "adreno") && !strcasestr(entry->d_name, "mali")
                && !strcasestr(entry->d_name, "g3d")) continue;
        char path[384];
        snprintf(path, sizeof path, "%s%s/cur_freq", base, entry->d_name);
        result = node(path);
        if (result <= 0) continue;
        if (*limit <= 0) {
            snprintf(path, sizeof path, "%s%s/max_freq", base, entry->d_name);
            *limit = node(path);
        }
        if (*maximum <= 0) {
            snprintf(path, sizeof path, "%s%s/available_frequencies", base, entry->d_name);
            *maximum = maximum_frequency(path);
        }
        break;
    }
    closedir(devices);
    return result;
}

static void gpu_stats(void) {
    char model[128];
    read_file(KGSL "gpu_model", model, sizeof model);
    long load = kgsl_gpu_load();
    long clock = node(KGSL "gpuclk");
    long limit = node(KGSL "max_gpuclk");
    long maximum = maximum_frequency(KGSL "gpu_available_frequencies");
    if (!*model) read_file(GPU_SYSFS "gpu_model", model, sizeof model);
    model[strcspn(model, "\r\n")] = 0;
    sanitize(model);
    if (load < 0) load = gpu_percent(GPU_SYSFS "gpu_busy");
    if (clock <= 0) {
        clock = mhz_to_hz(node(GPU_SYSFS "gpu_clock"));
        if (clock > 0) {
            if (limit <= 0) limit = mhz_to_hz(node(GPU_SYSFS "gpu_max_clock"));
            if (maximum <= 0) maximum = mhz_to_hz(maximum_frequency(GPU_SYSFS "gpu_freq_table"));
        }
    }
    if ((load < 0 || clock <= 0) && !strcasestr(model, "adreno")
            && mali_gpu_stats(&load, &clock, &limit, &maximum) && !*model) {
        strcpy(model, "Mali");
    }
    if (clock <= 0) clock = devfreq_gpu_clock(&limit, &maximum);
    printf("G\t%ld\t%ld\t%s\t%ld\t%ld\n", load, clock, model, limit, maximum);
}

static int snapshot(bool processes) {
    cpu_stats();
    memory_stats();
    gpu_stats();
    thermal_stats();
    frequency_stats();
    cooling_stats();
    if (!processes) { puts("END"); return 0; }

    DIR *proc = opendir("/proc");
    if (!proc) { perror("/proc"); return 1; }

    struct dirent *entry;
    while ((entry = readdir(proc))) {
        if (!isdigit((unsigned char)*entry->d_name)) continue;
        char *end;
        long pid = strtol(entry->d_name, &end, 10);
        struct task task = {0};
        if (*end || !read_task(pid, &task) || task.tgid != pid) continue;
        printf("P\t%ld\t%ld\t%lu\t%c\t%ld\t%llu\t%llu\t%ld\t%ld\t%lu\t%s\n",
               pid, task.ppid, task.uid, task.state, task.rss, task.start, task.ticks,
               task.threads, task.nice, task.flags, task.name);
    }
    closedir(proc);
    puts("END");
    return 0;
}

static void task_memory(long pid, bool rollup, long long *pss, long long *uss) {
    char path[128], line[512];
    snprintf(path, sizeof path, "/proc/%ld/%s", pid, rollup ? "smaps_rollup" : "smaps");
    FILE *file = fopen(path, "re");
    if (!file) return;
    long long shared = -1, clean = -1, dirty = -1;
    while (fgets(line, sizeof line, file)) {
        long long amount;
        if (!strncmp(line, "Pss:", 4) && sscanf(line + 4, "%lld", &amount) == 1 && amount >= 0) {
            shared = (shared < 0 ? 0 : shared) + amount;
        } else if (!strncmp(line, "Private_Clean:", 14) && sscanf(line + 14, "%lld", &amount) == 1 && amount >= 0) {
            clean = (clean < 0 ? 0 : clean) + amount;
        } else if (!strncmp(line, "Private_Dirty:", 14) && sscanf(line + 14, "%lld", &amount) == 1 && amount >= 0) {
            dirty = (dirty < 0 ? 0 : dirty) + amount;
        }
    }
    if (!ferror(file)) {
        *pss = shared;
        *uss = clean >= 0 && dirty >= 0 ? clean + dirty : -1;
    }
    fclose(file);
}

static int process_memory(void) {
    DIR *proc = opendir("/proc");
    if (!proc) return 1;
    nice(10);
    bool rollup = access("/proc/self/smaps_rollup", F_OK) == 0;
    struct dirent *entry;
    while ((entry = readdir(proc))) {
        if (!numbered_entry(entry->d_name, "")) continue;
        long pid = strtol(entry->d_name, NULL, 10);
        struct task before = {0}, after = {0};
        if (!read_task(pid, &before) || before.tgid != pid || (before.flags & 0x00200000UL)) continue;
        long long pss = -1, uss = -1;
        task_memory(pid, rollup, &pss, &uss);
        if ((pss >= 0 || uss >= 0) && read_task(pid, &after) && after.start == before.start) {
            printf("%ld\t%llu\t%lld\t%lld\n", pid, before.start, pss, uss);
        }
    }
    closedir(proc);
    puts("END");
    return 0;
}

extern char **environ;

static void overlay_dump(const char *target, const char *option, const char *value) {
    const char *args[] = {"/system/bin/dumpsys", "-t", "2", target, option, value, NULL};
    fflush(stdout);
    pid_t child;
    if (posix_spawn(&child, args[0], NULL, NULL, (char *const *)args, environ) != 0) return;
    int status;
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) { }
    putchar('\n');
}

static int overlay_stream(void) {
    char command[384];
    while (fgets(command, sizeof command, stdin)) {
        command[strcspn(command, "\r\n")] = 0;
        if (command[0] == 'm' && command[1] == ' ') {
            long mask = strtol(command + 2, NULL, 10);
            cpu_stats();
            if (mask & 1) memory_stats();
            if (mask & 2) gpu_stats();
            if (mask & 4) frequency_stats();
            if (mask & 8) thermal_stats();
            puts("END");
        } else if (!strcmp(command, "f")) {
            overlay_dump("activity", "activities", NULL);
            puts("TASKSCOPE_FRAME_DATA");
            overlay_dump("SurfaceFlinger", "--frametimeline", "-all");
        } else if (!strcmp(command, "l")) {
            overlay_dump("SurfaceFlinger", "--list", NULL);
        } else if (command[0] == 'a' && command[1] == ' ' && command[2]) {
            overlay_dump("SurfaceFlinger", "--latency", command + 2);
        }
        puts("TASKSCOPE_OVERLAY_END");
        fflush(stdout);
    }
    return 0;
}

static int details(long pid, unsigned long long expected) {
    struct task task = {0};
    if (!read_task(pid, &task) || task.start != expected) {
        fputs("Процесс уже завершился или PID сменился.\n", stderr);
        return 1;
    }
    const char *files[] = {"status", "io", "cgroup", "cmdline"};
    for (size_t i = 0; i < sizeof files / sizeof files[0]; ++i) {
        char path[128], data[8192];
        snprintf(path, sizeof path, "/proc/%ld/%s", pid, files[i]);
        size_t count = read_file(path, data, sizeof data);
        if (i == 3) {
            for (size_t j = 0; j < count; ++j) if (!data[j]) data[j] = ' ';
        }
        printf("── %s ──\n%s\n\n", files[i], count ? data : "—");
    }
    struct task after = {0};
    return !read_task(pid, &after) || after.start != expected;
}

static int signal_task(long pid, unsigned long long expected, int signal) {
    int fd = syscall(SYS_pidfd_open, pid, 0);
    if (fd < 0) { perror("pidfd_open"); return 1; }
    struct task task = {0};
    int result = 1;
    if (!read_task(pid, &task) || task.start != expected) {
        fputs("Процесс уже завершился или PID сменился.\n", stderr);
    } else if (pid <= 2 || (task.flags & 0x00200000UL)) {
        fputs("Поток ядра или init защищён.\n", stderr);
    } else if (syscall(SYS_pidfd_send_signal, fd, signal, NULL, 0) == 0) {
        result = 0;
    } else {
        perror("pidfd_send_signal");
    }
    close(fd);
    return result;
}

int main(int argc, char **argv) {
    if (geteuid() != 0) { fputs("Требуется root.\n", stderr); return 1; }
    if (argc == 2 && !strcmp(argv[1], "overlay-stream")) return overlay_stream();
    if (argc == 2 && !strcmp(argv[1], "snapshot")) return snapshot(true);
    if (argc == 2 && !strcmp(argv[1], "metrics")) return snapshot(false);
    if (argc == 2 && !strcmp(argv[1], "process-memory")) return process_memory();
    if (argc == 4) {
        long pid = strtol(argv[2], NULL, 10);
        unsigned long long start = strtoull(argv[3], NULL, 10);
        if (pid < 1) return 2;
        if (!strcmp(argv[1], "details")) return details(pid, start);
        if (!strcmp(argv[1], "term")) return signal_task(pid, start, 15);
        if (!strcmp(argv[1], "kill")) return signal_task(pid, start, 9);
        if (!strcmp(argv[1], "pause")) return signal_task(pid, start, 19);
        if (!strcmp(argv[1], "resume")) return signal_task(pid, start, 18);
    }
    return 2;
}
