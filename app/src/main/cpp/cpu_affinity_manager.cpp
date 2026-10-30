#include "cpu_affinity_manager.h"

#include "jni_common.h"

#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdlib>
#include <fstream>
#include <map>
#include <mutex>
#include <sstream>

namespace polylingua {
namespace {

std::string Trim(const std::string& s) {
    size_t a = 0, b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\r' || s[a] == '\n')) ++a;
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\r' || s[b - 1] == '\n')) --b;
    return s.substr(a, b - a);
}

bool ReadFirstLine(const std::string& path, std::string* out) {
    std::ifstream f(path);
    if (!f.good()) return false;
    std::string line;
    if (!std::getline(f, line)) return false;
    *out = Trim(line);
    return true;
}

long ReadLong(const std::string& path, long def) {
    std::string s;
    if (!ReadFirstLine(path, &s) || s.empty()) return def;
    char* end = nullptr;
    long v = std::strtol(s.c_str(), &end, 10);
    return end == s.c_str() ? def : v;
}

std::vector<int> ParseCpuList(const std::string& s) {
    std::vector<int> out;
    std::stringstream ss(s);
    std::string tok;
    while (std::getline(ss, tok, ',')) {
        tok = Trim(tok);
        if (tok.empty()) continue;
        size_t dash = tok.find('-');
        if (dash == std::string::npos) {
            out.push_back(std::atoi(tok.c_str()));
        } else {
            int a = std::atoi(tok.substr(0, dash).c_str());
            int b = std::atoi(tok.substr(dash + 1).c_str());
            for (int i = a; i <= b && i < 1024; ++i) out.push_back(i);
        }
    }
    return out;
}

int CurrentTid() { return static_cast<int>(syscall(__NR_gettid)); }

bool IsBigPart(int part) {
    switch (part) {
        case 0xd07:  // Cortex-A57
        case 0xd08:  // Cortex-A72
        case 0xd09:  // Cortex-A73
        case 0xd0a:  // Cortex-A75
        case 0xd0b:  // Cortex-A76  (Helio G96 performance cores)
        case 0xd0d:  // Cortex-A77
        case 0xd41:  // Cortex-A78
        case 0xd44:  // Cortex-X1
        case 0xd47:  // Cortex-A710
        case 0xd48:  // Cortex-X2
        case 0xd4d:  // Cortex-A715
        case 0xd4e:  // Cortex-X3
        case 0xd81:  // Cortex-A720
        case 0xd82:  // Cortex-X4
            return true;
        default:
            return false;
    }
}

bool IsLittlePart(int part) {
    switch (part) {
        case 0xd03:  // Cortex-A53
        case 0xd04:  // Cortex-A35
        case 0xd05:  // Cortex-A55  (Helio G96 efficiency cores)
        case 0xd46:  // Cortex-A510
        case 0xd80:  // Cortex-A520
            return true;
        default:
            return false;
    }
}

CpuTopology Detect() {
    CpuTopology t;
    std::vector<int> ids;
    std::string possible;
    if (ReadFirstLine("/sys/devices/system/cpu/possible", &possible)) ids = ParseCpuList(possible);
    if (ids.empty()) {
        long n = sysconf(_SC_NPROCESSORS_CONF);
        for (int i = 0; i < n; ++i) ids.push_back(i);
    }

    std::map<int, int> parts;
    {
        std::ifstream ci("/proc/cpuinfo");
        std::string line;
        int current = -1;
        while (std::getline(ci, line)) {
            size_t colon = line.find(':');
            if (colon == std::string::npos) continue;
            std::string key = Trim(line.substr(0, colon));
            std::string val = Trim(line.substr(colon + 1));
            if (key == "processor") {
                current = std::atoi(val.c_str());
            } else if (key == "CPU part" && current >= 0) {
                parts[current] = static_cast<int>(std::strtol(val.c_str(), nullptr, 0));
            } else if (key == "Hardware") {
                t.hardware = val;
            }
        }
    }

    // cpufreq policies also describe cores that are currently hot-unplugged.
    std::map<int, long> policyFreq;
    for (int p = 0; p < 16; ++p) {
        std::string base = "/sys/devices/system/cpu/cpufreq/policy" + std::to_string(p);
        std::string related;
        if (!ReadFirstLine(base + "/related_cpus", &related)) continue;
        long f = ReadLong(base + "/cpuinfo_max_freq", 0);
        for (int c : ParseCpuList(related)) policyFreq[c] = f;
    }

    for (int id : ids) {
        CpuCoreInfo c;
        c.id = id;
        std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(id);
        c.maxFreqKhz = ReadLong(base + "/cpufreq/cpuinfo_max_freq", 0);
        if (c.maxFreqKhz == 0) {
            auto it = policyFreq.find(id);
            if (it != policyFreq.end()) c.maxFreqKhz = it->second;
        }
        c.capacity = static_cast<int>(ReadLong(base + "/cpu_capacity", 0));
        auto pit = parts.find(id);
        if (pit != parts.end()) c.midrPart = pit->second;
        c.online = ReadLong(base + "/online", 1) != 0;  // cpu0 usually has no 'online' node
        t.cores.push_back(c);
    }

    const size_t n = t.cores.size();
    std::vector<long> score(n, 0);
    bool allCap = n > 0, allFreq = n > 0, anyBigPart = false, anyLittlePart = false;
    long capMin = LONG_MAX, capMax = 0, fMin = LONG_MAX, fMax = 0;
    for (const auto& c : t.cores) {
        if (c.capacity <= 0) allCap = false;
        if (c.maxFreqKhz <= 0) allFreq = false;
        capMin = std::min<long>(capMin, c.capacity);
        capMax = std::max<long>(capMax, c.capacity);
        fMin = std::min(fMin, c.maxFreqKhz);
        fMax = std::max(fMax, c.maxFreqKhz);
        if (IsBigPart(c.midrPart)) anyBigPart = true;
        if (IsLittlePart(c.midrPart)) anyLittlePart = true;
    }

    if (allCap && capMax != capMin) {
        t.method = "cpu_capacity";
        for (size_t i = 0; i < n; ++i) score[i] = t.cores[i].capacity;
    } else if (anyBigPart && anyLittlePart) {
        t.method = "midr_cpu_part";
        for (size_t i = 0; i < n; ++i) {
            int p = t.cores[i].midrPart;
            score[i] = IsBigPart(p) ? 2 : (IsLittlePart(p) ? 1 : 0);
        }
    } else if (allFreq && fMax != fMin) {
        t.method = "cpuinfo_max_freq";
        for (size_t i = 0; i < n; ++i) score[i] = t.cores[i].maxFreqKhz;
    } else {
        t.method = "fallback_highest_ids";
        for (size_t i = 0; i < n; ++i) score[i] = t.cores[i].id;  // big cores are last on most SoCs
    }

    long lowest = LONG_MAX;
    for (long s : score) lowest = std::min(lowest, s);

    std::vector<size_t> order(n);
    for (size_t i = 0; i < n; ++i) order[i] = i;
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        if (score[a] != score[b]) return score[a] > score[b];
        return t.cores[a].id > t.cores[b].id;
    });

    for (size_t idx : order) {
        bool candidate = (t.method == "fallback_highest_ids") || score[idx] > lowest;
        if (candidate && t.bigCores.size() < 2) t.bigCores.push_back(t.cores[idx].id);
    }
    if (t.bigCores.empty() && n > 0) t.bigCores.push_back(t.cores[order[0]].id);
    std::sort(t.bigCores.begin(), t.bigCores.end());
    for (const auto& c : t.cores) {
        if (std::find(t.bigCores.begin(), t.bigCores.end(), c.id) == t.bigCores.end()) {
            t.littleCores.push_back(c.id);
        }
    }
    return t;
}

}  // namespace

const char* MidrPartName(int part) {
    switch (part) {
        case 0xd03: return "Cortex-A53";
        case 0xd04: return "Cortex-A35";
        case 0xd05: return "Cortex-A55";
        case 0xd07: return "Cortex-A57";
        case 0xd08: return "Cortex-A72";
        case 0xd09: return "Cortex-A73";
        case 0xd0a: return "Cortex-A75";
        case 0xd0b: return "Cortex-A76";
        case 0xd0d: return "Cortex-A77";
        case 0xd41: return "Cortex-A78";
        case 0xd44: return "Cortex-X1";
        case 0xd46: return "Cortex-A510";
        case 0xd47: return "Cortex-A710";
        case 0xd48: return "Cortex-X2";
        case 0xd4d: return "Cortex-A715";
        case 0xd4e: return "Cortex-X3";
        case 0xd80: return "Cortex-A520";
        case 0xd81: return "Cortex-A720";
        case 0xd82: return "Cortex-X4";
        default: return "unknown";
    }
}

const CpuTopology& GetCpuTopology() {
    static CpuTopology topology;
    static std::once_flag once;
    std::call_once(once, [] {
        topology = Detect();
        std::string big, little;
        for (int c : topology.bigCores) big += std::to_string(c) + " ";
        for (int c : topology.littleCores) little += std::to_string(c) + " ";
        PL_LOGI("CPU topology via %s: big=[%s] little=[%s] hw=%s", topology.method.c_str(),
                big.c_str(), little.c_str(), topology.hardware.c_str());
    });
    return topology;
}

int PinCurrentThreadTo(const std::vector<int>& cores) {
    if (cores.empty()) return -ENOENT;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c : cores) {
        if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &set);
    }
    if (sched_setaffinity(CurrentTid(), sizeof(set), &set) != 0) {
        int err = errno;
        PL_LOGW("sched_setaffinity failed: errno=%d", err);
        return -err;
    }
    return 0;
}

int PinCurrentThreadToBigCores(int maxCores) {
    const auto& t = GetCpuTopology();
    std::vector<int> cores = t.bigCores;
    if (maxCores > 0 && static_cast<int>(cores.size()) > maxCores) {
        cores.erase(cores.begin(), cores.end() - maxCores);  // keep the highest ids
    }
    return PinCurrentThreadTo(cores);
}

int PinCurrentThreadToLittleCores() {
    const auto& t = GetCpuTopology();
    if (t.littleCores.empty()) return PinCurrentThreadTo(t.bigCores);
    return PinCurrentThreadTo(t.littleCores);
}

std::vector<int> GetCurrentThreadAffinity() {
    std::vector<int> out;
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(CurrentTid(), sizeof(set), &set) != 0) return out;
    for (int i = 0; i < CPU_SETSIZE && i < 64; ++i) {
        if (CPU_ISSET(i, &set)) out.push_back(i);
    }
    return out;
}

int RecommendedInferenceThreads() {
    const auto& t = GetCpuTopology();
    int n = static_cast<int>(t.bigCores.size());
    if (n <= 0) n = 2;
    return std::min(n, 2);
}

std::string TopologyToJson(const CpuTopology& t) {
    std::ostringstream o;
    o << "{\"method\":\"" << JsonEscape(t.method) << "\",\"hardware\":\"" << JsonEscape(t.hardware)
      << "\",\"big\":[";
    for (size_t i = 0; i < t.bigCores.size(); ++i) o << (i ? "," : "") << t.bigCores[i];
    o << "],\"little\":[";
    for (size_t i = 0; i < t.littleCores.size(); ++i) o << (i ? "," : "") << t.littleCores[i];
    o << "],\"cores\":[";
    for (size_t i = 0; i < t.cores.size(); ++i) {
        const auto& c = t.cores[i];
        char part[16];
        std::snprintf(part, sizeof(part), "0x%03x", c.midrPart);
        o << (i ? "," : "") << "{\"id\":" << c.id << ",\"maxFreqKhz\":" << c.maxFreqKhz
          << ",\"capacity\":" << c.capacity << ",\"part\":\"" << part << "\",\"name\":\""
          << MidrPartName(c.midrPart) << "\",\"online\":" << (c.online ? "true" : "false") << "}";
    }
    o << "]}";
    return o.str();
}

}  // namespace polylingua

using namespace polylingua;

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_polylingua_edge_nativebridge_CpuAffinityNative_nativeDescribeTopology(JNIEnv* env, jobject) {
    return NewStringUtf8Safe(env, TopologyToJson(GetCpuTopology()));
}

JNIEXPORT jint JNICALL
Java_com_polylingua_edge_nativebridge_CpuAffinityNative_nativePinToBigCores(JNIEnv*, jobject, jint maxCores) {
    return PinCurrentThreadToBigCores(maxCores);
}

JNIEXPORT jint JNICALL
Java_com_polylingua_edge_nativebridge_CpuAffinityNative_nativePinToLittleCores(JNIEnv*, jobject) {
    return PinCurrentThreadToLittleCores();
}

JNIEXPORT jintArray JNICALL
Java_com_polylingua_edge_nativebridge_CpuAffinityNative_nativeCurrentAffinity(JNIEnv* env, jobject) {
    std::vector<int> cores = GetCurrentThreadAffinity();
    jintArray arr = env->NewIntArray(static_cast<jsize>(cores.size()));
    if (arr != nullptr && !cores.empty()) {
        std::vector<jint> tmp(cores.begin(), cores.end());
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(tmp.size()), tmp.data());
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_polylingua_edge_nativebridge_CpuAffinityNative_nativeRecommendedThreads(JNIEnv*, jobject) {
    return RecommendedInferenceThreads();
}

}  // extern "C"
