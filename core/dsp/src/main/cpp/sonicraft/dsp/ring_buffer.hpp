/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

namespace sonicraft::dsp {

/**
 * 单生产者单消费者浮点环形缓冲（帧粒度）。
 *
 * 用途：内录接管模式（Mode B）中桥接 capture 线程与 AAudio 回调线程两个时钟域，
 * 由水位控制器配合异步重采样吸收时钟漂移（M2 实现）。
 * 契约：仅一个线程调 PushFrames，仅一个线程调 PopFrames。
 */
class SpscRingBuffer {
 public:
  explicit SpscRingBuffer(std::size_t capacity_frames)
      : capacity_(capacity_frames + 1), buffer_(capacity_ * 2) {}

  std::size_t PushFrames(const float* interleaved, std::size_t frames) {
    const std::size_t head = head_.load(std::memory_order_relaxed);
    const std::size_t tail = tail_.load(std::memory_order_acquire);
    const std::size_t free = FreeSpace(head, tail);
    const std::size_t to_push = frames < free ? frames : free;
    for (std::size_t i = 0; i < to_push; ++i) {
      const std::size_t pos = (head + i) % capacity_;
      buffer_[pos * 2] = interleaved[i * 2];
      buffer_[pos * 2 + 1] = interleaved[i * 2 + 1];
    }
    head_.store((head + to_push) % capacity_, std::memory_order_release);
    return to_push;
  }

  std::size_t PopFrames(float* interleaved, std::size_t frames) {
    const std::size_t tail = tail_.load(std::memory_order_relaxed);
    const std::size_t head = head_.load(std::memory_order_acquire);
    const std::size_t available = AvailableSpace(head, tail);
    const std::size_t to_pop = frames < available ? frames : available;
    for (std::size_t i = 0; i < to_pop; ++i) {
      const std::size_t pos = (tail + i) % capacity_;
      interleaved[i * 2] = buffer_[pos * 2];
      interleaved[i * 2 + 1] = buffer_[pos * 2 + 1];
    }
    tail_.store((tail + to_pop) % capacity_, std::memory_order_release);
    return to_pop;
  }

  std::size_t AvailableFrames() const {
    return AvailableSpace(head_.load(std::memory_order_acquire),
                          tail_.load(std::memory_order_acquire));
  }

  std::size_t CapacityFrames() const { return capacity_ - 1; }

 private:
  std::size_t AvailableSpace(std::size_t head, std::size_t tail) const {
    return head >= tail ? head - tail : capacity_ - (tail - head);
  }
  std::size_t FreeSpace(std::size_t head, std::size_t tail) const {
    return CapacityFrames() - AvailableSpace(head, tail);
  }

  std::size_t capacity_;
  std::vector<float> buffer_;  // 帧内固定双声道
  std::atomic<std::size_t> head_{0};
  std::atomic<std::size_t> tail_{0};
};

}  // namespace sonicraft::dsp
