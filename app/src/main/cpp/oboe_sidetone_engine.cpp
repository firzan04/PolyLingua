// Zero-latency vocal monitoring ("sidetone") for wired 3.5 mm headsets, built on Oboe.
//
// One full-duplex pair: a non-callback LowLatency input stream is drained inside the output
// stream's data callback, so the learner hears their own voice ~10-20 ms later while practising
// shadowing / phoneme drills. The same captured samples are low-pass filtered, resampled to
// 16 kHz mono PCM16 and pushed into a lock-free SPSC ring that the Kotlin capture thread drains
// - Android allows only one active capture, so the duplex engine also feeds ASR/VAD/pitch.
#include "jni_common.h"

#include <cstring>  // Oboe headers use memset without including it

#include <oboe/Oboe.h>

#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <vector>

using namespace polylingua;

namespace {

constexpr int kTargetRate = 16000;
constexpr int kMaxCallbackFrames = 8192;

class SpscRing {
public:
    explicit SpscRing(size_t capacity) : buf_(capacity), cap_(capacity) {}
    void Push(int16_t v) {
        size_t h = head_.load(std::memory_order_relaxed);
        size_t next = (h + 1) % cap_;
        if (next == tail_.load(std::memory_order_acquire)) {
            overruns_.fetch_add(1, std::memory_order_relaxed);
            return;  // reader is behind: drop newest sample rather than block the audio thread
        }
        buf_[h] = v;
        head_.store(next, std::memory_order_release);
    }
    size_t Pop(int16_t* out, size_t max) {
        size_t t = tail_.load(std::memory_order_relaxed);
        const size_t h = head_.load(std::memory_order_acquire);
        size_t n = 0;
        while (t != h && n < max) {
            out[n++] = buf_[t];
            t = (t + 1) % cap_;
        }
        tail_.store(t, std::memory_order_release);
        return n;
    }
    void Clear() { tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release); }

private:
    std::vector<int16_t> buf_;
    size_t cap_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
    std::atomic<uint64_t> overruns_{0};
};

// Windowed-sinc low-pass at the device rate followed by linear-interpolated decimation to 16 kHz.
class Downsampler {
public:
    void Configure(int inputRate) {
        inputRate_ = inputRate > 0 ? inputRate : 48000;
        step_ = static_cast<double>(inputRate_) / kTargetRate;
        const int taps = 47;
        const double cutoff = 7000.0 / inputRate_;  // normalised (cycles/sample)
        coeffs_.assign(taps, 0.0f);
        double sum = 0.0;
        for (int i = 0; i < taps; ++i) {
            const double m = i - (taps - 1) / 2.0;
            const double sinc = m == 0.0 ? 2.0 * cutoff : std::sin(2.0 * M_PI * cutoff * m) / (M_PI * m);
            const double window = 0.54 - 0.46 * std::cos(2.0 * M_PI * i / (taps - 1));
            coeffs_[i] = static_cast<float>(sinc * window);
            sum += coeffs_[i];
        }
        for (float& c : coeffs_) c = static_cast<float>(c / sum);
        history_.assign(taps, 0.0f);
        hpos_ = 0;
        index_ = 0;
        nextOut_ = 0.0;
        prev_ = 0.0f;
    }

    template <typename Sink>
    void Process(const float* in, int n, Sink&& sink) {
        const int taps = static_cast<int>(coeffs_.size());
        for (int i = 0; i < n; ++i) {
            history_[hpos_] = in[i];
            float y = 0.0f;
            int idx = hpos_;
            for (int k = 0; k < taps; ++k) {
                y += coeffs_[k] * history_[idx];
                idx = idx == 0 ? taps - 1 : idx - 1;
            }
            hpos_ = (hpos_ + 1) % taps;
            const double k = static_cast<double>(index_);
            while (nextOut_ <= k) {
                const double frac = nextOut_ - (k - 1.0);
                const float v = prev_ + static_cast<float>(frac) * (y - prev_);
                sink(v);
                nextOut_ += step_;
            }
            prev_ = y;
            ++index_;
        }
    }

private:
    int inputRate_ = 48000;
    double step_ = 3.0;
    std::vector<float> coeffs_;
    std::vector<float> history_;
    int hpos_ = 0;
    uint64_t index_ = 0;
    double nextOut_ = 0.0;
    float prev_ = 0.0f;
};

class SidetoneEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    SidetoneEngine() : ring_(kTargetRate * 20) { inBuf_.resize(kMaxCallbackFrames); }

    int Start(float gain, int inputDeviceId, int outputDeviceId,
              const std::shared_ptr<SidetoneEngine>& self) {
        std::lock_guard<std::mutex> lock(mutex_);
        StopLocked();
        gain_.store(gain);
        disconnected_.store(false);

        oboe::AudioStreamBuilder out;
        out.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Speech)
                ->setDataCallback(self)
                ->setErrorCallback(self);
        if (outputDeviceId > 0) out.setDeviceId(outputDeviceId);
        oboe::Result r = out.openStream(output_);
        if (r != oboe::Result::OK || !output_) {
            PL_LOGE("Oboe output open failed: %s", oboe::convertToText(r));
            return -1;
        }
        const int rate = output_->getSampleRate();

        oboe::AudioStreamBuilder in;
        in.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setSampleRate(rate)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setInputPreset(oboe::InputPreset::VoicePerformance);
        if (inputDeviceId > 0) in.setDeviceId(inputDeviceId);
        r = in.openStream(input_);
        if (r != oboe::Result::OK || !input_) {
            PL_LOGE("Oboe input open failed: %s", oboe::convertToText(r));
            output_->close();
            output_.reset();
            return -2;
        }
        // Keep the output buffer at two bursts: lowest glitch-free latency on MediaTek AAudio.
        output_->setBufferSizeInFrames(output_->getFramesPerBurst() * 2);
        downsampler_.Configure(rate);
        ring_.Clear();

        r = input_->requestStart();
        if (r != oboe::Result::OK) {
            PL_LOGE("Oboe input start failed: %s", oboe::convertToText(r));
            StopLocked();
            return -3;
        }
        r = output_->requestStart();
        if (r != oboe::Result::OK) {
            PL_LOGE("Oboe output start failed: %s", oboe::convertToText(r));
            StopLocked();
            return -4;
        }
        running_.store(true);
        PL_LOGI("Sidetone running at %d Hz, burst=%d", rate, output_->getFramesPerBurst());
        return 0;
    }

    void Stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        StopLocked();
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        auto* out = static_cast<float*>(audioData);
        int32_t remaining = numFrames;
        int32_t written = 0;
        const float g = monitoring_.load() ? gain_.load() : 0.0f;
        while (remaining > 0) {
            const int32_t chunk = remaining > kMaxCallbackFrames ? kMaxCallbackFrames : remaining;
            int32_t got = 0;
            if (input_) {
                auto res = input_->read(inBuf_.data(), chunk, 0);
                if (res) got = res.value();
            }
            for (int32_t i = 0; i < got; ++i) out[written + i] = inBuf_[i] * g;
            for (int32_t i = got; i < chunk; ++i) out[written + i] = 0.0f;
            if (got > 0) {
                downsampler_.Process(inBuf_.data(), got, [this](float v) {
                    float c = v * 32767.0f;
                    if (c > 32767.0f) c = 32767.0f;
                    if (c < -32768.0f) c = -32768.0f;
                    ring_.Push(static_cast<int16_t>(c));
                });
            }
            written += chunk;
            remaining -= chunk;
        }
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        PL_LOGW("Sidetone stream closed: %s", oboe::convertToText(error));
        disconnected_.store(true);
        running_.store(false);
    }

    int Read(int16_t* out, int max) { return static_cast<int>(ring_.Pop(out, static_cast<size_t>(max))); }
    void SetGain(float g) { gain_.store(g); }
    void SetMonitoring(bool on) { monitoring_.store(on); }
    bool IsRunning() const { return running_.load() && !disconnected_.load(); }

    double LatencyMs() {
        std::lock_guard<std::mutex> lock(mutex_);
        double total = 0.0;
        if (output_) {
            auto l = output_->calculateLatencyMillis();
            if (l) total += l.value();
        }
        if (input_) {
            auto l = input_->calculateLatencyMillis();
            if (l) total += l.value();
        }
        return total;
    }

    int SampleRate() {
        std::lock_guard<std::mutex> lock(mutex_);
        return output_ ? output_->getSampleRate() : 0;
    }

private:
    void StopLocked() {
        running_.store(false);
        if (output_) {
            output_->requestStop();
            output_->close();
            output_.reset();
        }
        if (input_) {
            input_->requestStop();
            input_->close();
            input_.reset();
        }
    }

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> input_;
    std::shared_ptr<oboe::AudioStream> output_;
    std::vector<float> inBuf_;
    Downsampler downsampler_;
    SpscRing ring_;
    std::atomic<float> gain_{0.8f};
    std::atomic<bool> monitoring_{true};
    std::atomic<bool> running_{false};
    std::atomic<bool> disconnected_{false};
};

struct Holder {
    std::shared_ptr<SidetoneEngine> engine;
};

Holder* HolderOf(jlong h) { return reinterpret_cast<Holder*>(static_cast<intptr_t>(h)); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeCreate(JNIEnv*, jobject) {
    auto* holder = new Holder();
    holder->engine = std::make_shared<SidetoneEngine>();
    return static_cast<jlong>(reinterpret_cast<intptr_t>(holder));
}

JNIEXPORT jint JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeStart(JNIEnv*, jobject, jlong h, jfloat gain,
                                                             jint inputDeviceId, jint outputDeviceId) {
    Holder* holder = HolderOf(h);
    if (holder == nullptr) return -10;
    return holder->engine->Start(gain, inputDeviceId, outputDeviceId, holder->engine);
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeStop(JNIEnv*, jobject, jlong h) {
    Holder* holder = HolderOf(h);
    if (holder != nullptr) holder->engine->Stop();
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeSetGain(JNIEnv*, jobject, jlong h, jfloat gain) {
    Holder* holder = HolderOf(h);
    if (holder != nullptr) holder->engine->SetGain(gain);
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeSetMonitoring(JNIEnv*, jobject, jlong h, jboolean on) {
    Holder* holder = HolderOf(h);
    if (holder != nullptr) holder->engine->SetMonitoring(on == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeRead(JNIEnv* env, jobject, jlong h, jshortArray buf, jint max) {
    Holder* holder = HolderOf(h);
    if (holder == nullptr || buf == nullptr) return 0;
    jsize len = env->GetArrayLength(buf);
    if (max > len) max = len;
    if (max <= 0) return 0;
    std::vector<int16_t> tmp(static_cast<size_t>(max));
    int n = holder->engine->Read(tmp.data(), max);
    if (n > 0) env->SetShortArrayRegion(buf, 0, n, tmp.data());
    return n;
}

JNIEXPORT jdouble JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeLatencyMs(JNIEnv*, jobject, jlong h) {
    Holder* holder = HolderOf(h);
    return holder == nullptr ? 0.0 : holder->engine->LatencyMs();
}

JNIEXPORT jboolean JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeIsRunning(JNIEnv*, jobject, jlong h) {
    Holder* holder = HolderOf(h);
    return (holder != nullptr && holder->engine->IsRunning()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeSampleRate(JNIEnv*, jobject, jlong h) {
    Holder* holder = HolderOf(h);
    return holder == nullptr ? 0 : holder->engine->SampleRate();
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_OboeNative_nativeDestroy(JNIEnv*, jobject, jlong h) {
    Holder* holder = HolderOf(h);
    if (holder == nullptr) return;
    holder->engine->Stop();
    delete holder;
}

}  // extern "C"
