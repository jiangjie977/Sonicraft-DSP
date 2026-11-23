/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <array>
#include <atomic>
#include <cstddef>

namespace sonicraft::dsp {

/** 单生产者单消费者无锁队列；容量必须为 2 的幂。 */
template <typename T, std::size_t kCapacity>
class SpscQueue {
  static_assert((kCapacity & (kCapacity - 1)) == 0, "capacity must be power of two");

 public:
  bool Push(const T& value) {
    const std::size_t head = head_.load(std::memory_order_relaxed);
    const std::size_t next = (head + 1) & kMask;
    if (next == tail_.load(std::memory_order_acquire)) return false;  // 满：丢弃
    slots_[head] = value;
    head_.store(next, std::memory_order_release);
    return true;
  }

  bool Pop(T* out) {
    const std::size_t tail = tail_.load(std::memory_order_relaxed);
    if (tail == head_.load(std::memory_order_acquire)) return false;  // 空
    *out = slots_[tail];
    tail_.store((tail + 1) & kMask, std::memory_order_release);
    return true;
  }

  bool Empty() const { return tail_.load(std::memory_order_acquire) == head_.load(std::memory_order_acquire); }

 private:
  static constexpr std::size_t kMask = kCapacity - 1;
  std::array<T, kCapacity> slots_{};
  std::atomic<std::size_t> head_{0};
  std::atomic<std::size_t> tail_{0};
};

/** 参数命令：实时线程消费，应用到指定节点 */
struct ParamCommand {
  int node_index;
  int param_id;
  float value;
  bool is_bypass;
};

using ParamQueue = SpscQueue<ParamCommand, 256>;

}  // namespace sonicraft::dsp
