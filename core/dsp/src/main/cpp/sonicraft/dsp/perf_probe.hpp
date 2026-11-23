/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <atomic>
#include <cstdint>

namespace sonicraft::dsp {

/**
 * 实时线程安全的性能探针：仅用 relaxed 原子累加，无锁无分配。
 * 统计对象为 DspGraph::process() 单次耗时。
 */
class PerfProbe {
 public:
  void Record(std::uint64_t nanos) {
    last_ns_.store(nanos, std::memory_order_relaxed);
    std::uint64_t prev = max_ns_.load(std::memory_order_relaxed);
    while (nanos > prev &&
           !max_ns_.compare_exchange_weak(prev, nanos, std::memory_order_relaxed)) {
    }
    total_ns_.fetch_add(nanos, std::memory_order_relaxed);
    count_.fetch_add(1, std::memory_order_relaxed);
  }

  std::uint64_t LastProcessNanos() const { return last_ns_.load(std::memory_order_relaxed); }
  std::uint64_t MaxProcessNanos() const { return max_ns_.load(std::memory_order_relaxed); }
  std::uint64_t TotalProcessNanos() const { return total_ns_.load(std::memory_order_relaxed); }
  std::uint64_t ProcessCount() const { return count_.load(std::memory_order_relaxed); }

  void Reset() {
    last_ns_.store(0, std::memory_order_relaxed);
    max_ns_.store(0, std::memory_order_relaxed);
    total_ns_.store(0, std::memory_order_relaxed);
    count_.store(0, std::memory_order_relaxed);
  }

 private:
  std::atomic<std::uint64_t> last_ns_{0};
  std::atomic<std::uint64_t> max_ns_{0};
  std::atomic<std::uint64_t> total_ns_{0};
  std::atomic<std::uint64_t> count_{0};
};

}  // namespace sonicraft::dsp
