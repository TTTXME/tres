#pragma once
#include <vulkan/vulkan_core.h>
#include "ScameraFilmPolicy.h"

namespace spektrafilm::queue_policy {
struct Creation {
  VkDevice device = VK_NULL_HANDLE;
  VkResult result = VK_ERROR_INITIALIZATION_FAILED;
  VkResult lowResult = VK_NOT_READY;
  uint32_t family = 0;
  const char* extension = nullptr;
  bool attempted = false;
  bool accepted = false;
};

inline const char* lowExtension(uint32_t instanceApi, bool khr, bool ext) {
  if (khr && instanceApi >= VK_API_VERSION_1_1) return VK_KHR_GLOBAL_PRIORITY_EXTENSION_NAME;
  return ext ? VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME : nullptr;
}

inline Creation create(VkPhysicalDevice physical, const VkDeviceQueueCreateInfo& originalQueue,
                       const VkQueueFamilyProperties* families, uint32_t familyCount,
                       uint32_t vendor, uint32_t instanceApi, QueuePolicy policy,
                       bool khr, bool ext, PFN_vkCreateDevice driver) {
  Creation output;
  output.family = originalQueue.queueFamilyIndex;
  const char* extension = policy == QueuePolicy::Interactive && vendor == 0x5143
      ? lowExtension(instanceApi, khr, ext) : nullptr;
  uint32_t general = familyCount;
  if (extension) {
    for (uint32_t i = 0; i < familyCount; ++i) {
      const auto required = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
      if ((families[i].queueFlags & required) == required
          && families[i].queueCount >= originalQueue.queueCount) {
        general = i;
        break;
      }
    }
  }

  VkDeviceQueueCreateInfo queue = originalQueue;
  VkDeviceCreateInfo deviceInfo{};
  deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
  deviceInfo.queueCreateInfoCount = 1;
  deviceInfo.pQueueCreateInfos = &queue;
  VkDeviceQueueGlobalPriorityCreateInfoKHR priority{};
  priority.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_KHR;
  priority.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_LOW_KHR;
  if (general < familyCount) {
    queue.queueFamilyIndex = general;
    queue.pNext = &priority;
    deviceInfo.enabledExtensionCount = 1;
    deviceInfo.ppEnabledExtensionNames = &extension;
    output.extension = extension;
    output.attempted = true;
  }
  VkDevice created = VK_NULL_HANDLE;
  output.result = driver(physical, &deviceInfo, nullptr, &created);
  if (output.attempted) {
    output.lowResult = output.result;
    output.accepted = output.result == VK_SUCCESS;
    if (output.accepted) output.family = general;
    else {
      // Restore both the original family and its ordinary priority on rejection.
      queue = originalQueue;
      deviceInfo.enabledExtensionCount = 0;
      deviceInfo.ppEnabledExtensionNames = nullptr;
      created = VK_NULL_HANDLE;
      output.result = driver(physical, &deviceInfo, nullptr, &created);
    }
  }
  if (output.result == VK_SUCCESS) output.device = created;
  return output;
}
}
