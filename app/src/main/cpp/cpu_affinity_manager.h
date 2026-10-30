// Heterogeneous CPU topology detection and thread pinning for big.LITTLE SoCs.
//
// Target: MediaTek Helio G96 = 2x Cortex-A76 @ 2.05 GHz (cpu6, cpu7) + 6x Cortex-A55 @ 2.0 GHz
// (cpu0..cpu5). Running more neural-inference threads than there are big cores lets the
// scheduler spread a barrier-synchronised workload across both clusters, where the slowest
// A55 thread gates every barrier. We therefore run llama.cpp / ONNX Runtime with exactly
// two threads and pin the *creating* thread to the big cores: Linux threads inherit the CPU
// affinity mask of the thread that spawns them, so ORT intra-op pools and ggml worker threads
// created from a pinned thread stay on the A76 cluster as well.
#pragma once

#include <string>
#include <vector>

namespace polylingua {

struct CpuCoreInfo {
    int id = 0;
    long maxFreqKhz = 0;   // cpufreq/cpuinfo_max_freq (0 if unknown)
    int capacity = 0;      // EAS cpu_capacity (0 if unknown)
    int midrPart = 0;      // MIDR "CPU part" from /proc/cpuinfo (0 if unknown)
    bool online = true;
};

struct CpuTopology {
    std::vector<CpuCoreInfo> cores;
    std::vector<int> bigCores;     // performance cluster (at most 2 kept for inference)
    std::vector<int> littleCores;  // efficiency cluster
    std::string method;            // which signal decided the split
    std::string hardware;          // "Hardware" line of /proc/cpuinfo when present
};

// Detected once and cached (thread-safe).
const CpuTopology& GetCpuTopology();

// Pins the calling thread to the given CPU ids. Returns 0 on success or -errno.
int PinCurrentThreadTo(const std::vector<int>& cores);

// Pins the calling thread to (at most `maxCores`) performance cores. Returns 0 or -errno.
int PinCurrentThreadToBigCores(int maxCores);

// Pins the calling thread to the efficiency cluster. Returns 0 or -errno.
int PinCurrentThreadToLittleCores();

// CPU ids the calling thread is currently allowed to run on.
std::vector<int> GetCurrentThreadAffinity();

// Number of threads heavy inference should use (= number of big cores, capped at 2).
int RecommendedInferenceThreads();

std::string TopologyToJson(const CpuTopology& t);

const char* MidrPartName(int part);

}  // namespace polylingua
