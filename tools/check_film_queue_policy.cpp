#include "../app/src/main/cpp/third_party/spektrafilm/ScameraQueuePolicy.h"

using namespace spektrafilm;
namespace {
struct Driver {
  int calls = 0;
  VkResult results[2] = {VK_SUCCESS, VK_SUCCESS};
  uint32_t family[2] = {}, extensions[2] = {}, count[2] = {};
  bool hasPriority[2] = {}, outputNull[2] = {}, low[2] = {};
  int extension[2] = {};
  const float* priorities[2] = {};
} driver;

bool equal(const char* a, const char* b) {
  while (*a && *a == *b) { ++a; ++b; }
  return *a == *b;
}

VkResult VKAPI_CALL createDevice(VkPhysicalDevice, const VkDeviceCreateInfo* info,
                                 const VkAllocationCallbacks*, VkDevice* output) {
  const int call = driver.calls++;
  if (call > 1) return VK_ERROR_UNKNOWN;
  const auto& queue = info->pQueueCreateInfos[0];
  driver.family[call] = queue.queueFamilyIndex;
  driver.count[call] = queue.queueCount;
  driver.extensions[call] = info->enabledExtensionCount;
  driver.hasPriority[call] = queue.pNext != nullptr;
  driver.outputNull[call] = *output == VK_NULL_HANDLE;
  driver.priorities[call] = queue.pQueuePriorities;
  if (queue.pNext) {
    const auto* priority = static_cast<const VkDeviceQueueGlobalPriorityCreateInfoKHR*>(queue.pNext);
    driver.low[call] = priority->sType == VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_KHR
        && priority->globalPriority == VK_QUEUE_GLOBAL_PRIORITY_LOW_KHR;
  }
  if (info->enabledExtensionCount == 1) {
    driver.extension[call] = equal(info->ppEnabledExtensionNames[0], VK_KHR_GLOBAL_PRIORITY_EXTENSION_NAME) ? 1
        : equal(info->ppEnabledExtensionNames[0], VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME) ? 2 : 3;
  }
  *output = reinterpret_cast<VkDevice>(driver.results[call] == VK_SUCCESS ? uintptr_t(0x101 + call) : uintptr_t(0xbad));
  return driver.results[call];
}

struct Fixture {
  VkQueueFamilyProperties families[2] = {};
  float priorities[2] = {1.0f, 0.75f};
  VkDeviceQueueCreateInfo original{};
  QueuePolicy policy = QueuePolicy::Interactive;
  uint32_t vendor = 0x5143, api = VK_API_VERSION_1_1;
  bool khr = true, ext = true;
  Fixture() {
    driver = {};
    families[0].queueFlags = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
    families[0].queueCount = 3;
    families[1].queueFlags = VK_QUEUE_COMPUTE_BIT;
    families[1].queueCount = 2;
    original.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    original.queueFamilyIndex = 1;
    original.queueCount = 1;
    original.pQueuePriorities = priorities;
  }
  queue_policy::Creation run() {
    return queue_policy::create(VK_NULL_HANDLE, original, families, 2, vendor, api,
                                policy, khr, ext, createDevice);
  }
};

struct Handle {
  int value = 0, *destroyed = nullptr;
  explicit operator bool() const { return value != 0; }
  void reset() { if (value) ++*destroyed; value = 0; }
};
struct Cache {
  Handle renderer;
  int resourceDirectory = 0;
  QueuePolicy queuePolicy = QueuePolicy::Legacy;
};
}

#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (false)
#if defined(_WIN32)
#define EXPORT extern "C" __declspec(dllexport)
extern "C" { int _fltused = 0; }
#else
#define EXPORT extern "C"
#endif

EXPORT int filmQueueCase(int test) {
  Fixture f;
  switch (test) {
    case 0: {
      f.policy = QueuePolicy::Legacy;
      auto r = f.run();
      CHECK(driver.calls == 1 && driver.family[0] == 1 && !driver.hasPriority[0]);
      CHECK(!r.attempted && !r.accepted && r.family == 1 && r.result == VK_SUCCESS);
      break;
    }
    case 1: {
      auto r = f.run();
      CHECK(driver.calls == 1 && driver.family[0] == 0 && driver.low[0]);
      CHECK(driver.extension[0] == 1 && r.accepted && r.family == 0);
      break;
    }
    case 2: {
      f.khr = false;
      auto r = f.run();
      CHECK(r.accepted && driver.extension[0] == 2 && driver.low[0]);
      break;
    }
    case 3: {
      f.khr = f.ext = false;
      auto r = f.run();
      CHECK(!r.attempted && driver.family[0] == 1 && driver.extensions[0] == 0);
      break;
    }
    case 4: {
      f.vendor = 0x13b5;
      auto r = f.run();
      CHECK(!r.attempted && r.family == 1 && !driver.hasPriority[0]);
      break;
    }
    case 5: {
      f.families[0].queueCount = 0;
      auto r = f.run();
      CHECK(!r.attempted && driver.family[0] == 1);
      break;
    }
    case 6: {
      f.families[0].queueFlags = VK_QUEUE_COMPUTE_BIT;
      auto r = f.run();
      CHECK(!r.attempted && driver.family[0] == 1);
      break;
    }
    case 7: {
      f.families[0].queueFlags = VK_QUEUE_GRAPHICS_BIT;
      auto r = f.run();
      CHECK(!r.attempted && driver.family[0] == 1);
      break;
    }
    case 8: {
      f.original.queueCount = 2;
      f.families[0].queueCount = 1;
      auto r = f.run();
      CHECK(!r.attempted && driver.family[0] == 1 && driver.count[0] == 2);
      break;
    }
    case 9: {
      f.api = VK_API_VERSION_1_0; f.ext = false;
      auto r = f.run();
      CHECK(!r.attempted && driver.extension[0] == 0 && r.family == 1);
      break;
    }
    case 10: {
      f.api = VK_API_VERSION_1_0;
      auto r = f.run();
      CHECK(r.accepted && driver.extension[0] == 2);
      break;
    }
    case 11: {
      driver.results[0] = VK_ERROR_NOT_PERMITTED_KHR;
      auto r = f.run();
      CHECK(driver.calls == 2 && r.attempted && !r.accepted && r.lowResult == VK_ERROR_NOT_PERMITTED_KHR);
      CHECK(driver.family[0] == 0 && driver.family[1] == 1 && r.family == 1);
      CHECK(!driver.hasPriority[1] && driver.extensions[1] == 0 && driver.outputNull[1]);
      CHECK(r.result == VK_SUCCESS && r.device == reinterpret_cast<VkDevice>(uintptr_t(0x102)));
      break;
    }
    case 12: {
      driver.results[0] = VK_ERROR_INITIALIZATION_FAILED;
      auto r = f.run();
      CHECK(driver.calls == 2 && !r.accepted && driver.family[1] == 1);
      CHECK(!driver.hasPriority[1] && driver.extensions[1] == 0 && driver.outputNull[1]);
      break;
    }
    case 13: {
      driver.results[0] = VK_ERROR_NOT_PERMITTED_KHR;
      driver.results[1] = VK_ERROR_OUT_OF_DEVICE_MEMORY;
      auto r = f.run();
      CHECK(driver.calls == 2 && r.result == VK_ERROR_OUT_OF_DEVICE_MEMORY && r.device == VK_NULL_HANDLE);
      break;
    }
    case 14: {
      f.policy = QueuePolicy::Legacy;
      driver.results[0] = VK_ERROR_INITIALIZATION_FAILED;
      auto r = f.run();
      CHECK(driver.calls == 1 && !r.attempted && r.device == VK_NULL_HANDLE);
      break;
    }
    case 15: {
      f.families[1].queueFlags |= VK_QUEUE_GRAPHICS_BIT;
      f.families[0].queueCount = 0;
      auto r = f.run();
      CHECK(r.accepted && r.family == 1 && driver.low[0]);
      break;
    }
    case 16: {
      f.original.queueCount = 2;
      auto r = f.run();
      CHECK(r.accepted && driver.count[0] == 2 && driver.priorities[0] == f.priorities);
      break;
    }
    case 17: {
      f.khr = false;
      driver.results[0] = VK_ERROR_NOT_PERMITTED_KHR;
      auto r = f.run();
      CHECK(driver.extension[0] == 2 && !r.accepted && driver.family[1] == 1);
      CHECK(!driver.hasPriority[1] && driver.extensions[1] == 0);
      break;
    }
    case 18: {
      f.vendor = 0;
      auto r = f.run();
      CHECK(!r.attempted && driver.family[0] == 1);
      break;
    }
    case 19: {
      int destroyed = 0, built = 0;
      Cache state;
      auto make = [&] { return Handle{++built, &destroyed}; };
      selectCachedFilmRenderer(state, 10, QueuePolicy::Legacy, make);
      selectCachedFilmRenderer(state, 10, QueuePolicy::Legacy, make);
      CHECK(built == 1 && destroyed == 0 && state.renderer.value == 1);
      break;
    }
    case 20: {
      int destroyed = 0, built = 0;
      Cache state;
      auto make = [&] { return Handle{++built, &destroyed}; };
      selectCachedFilmRenderer(state, 10, QueuePolicy::Legacy, make);
      selectCachedFilmRenderer(state, 10, QueuePolicy::Interactive, make);
      CHECK(built == 2 && destroyed == 1 && state.queuePolicy == QueuePolicy::Interactive);
      selectCachedFilmRenderer(state, 10, QueuePolicy::Legacy, make);
      CHECK(built == 3 && destroyed == 2 && state.queuePolicy == QueuePolicy::Legacy);
      break;
    }
    case 21: {
      int destroyed = 0, built = 0;
      Cache state;
      auto make = [&] { return Handle{++built, &destroyed}; };
      selectCachedFilmRenderer(state, 10, QueuePolicy::Interactive, make);
      selectCachedFilmRenderer(state, 20, QueuePolicy::Interactive, make);
      CHECK(built == 2 && destroyed == 1 && state.resourceDirectory == 20);
      break;
    }
    case 22: {
      int destroyed = 0, built = 0;
      Cache state;
      selectCachedFilmRenderer(state, 10, QueuePolicy::Interactive, [] { return Handle{}; });
      selectCachedFilmRenderer(state, 10, QueuePolicy::Interactive, [&] { return Handle{++built, &destroyed}; });
      CHECK(built == 1 && state.renderer.value == 1);
      break;
    }
    case 23: {
      int destroyed = 0, built = 0;
      bool releasedBeforeBuild = false;
      Cache state;
      auto make = [&] { return Handle{++built, &destroyed}; };
      selectCachedFilmRenderer(state, 10, QueuePolicy::Legacy, make);
      selectCachedFilmRenderer(state, 10, QueuePolicy::Interactive, [&] {
        releasedBeforeBuild = !state.renderer && destroyed == 1;
        return make();
      });
      CHECK(releasedBeforeBuild && built == 2);
      break;
    }
    default: return -1;
  }
  return 0;
}

#if !defined(_WIN32)
#include <cstdio>
int main() {
  for (int test = 0; test < 24; ++test) {
    const int line = filmQueueCase(test);
    if (line) { std::printf("FAIL film queue case %d at line %d\n", test, line); return 1; }
  }
  std::puts("PASS 24 film queue selection, fallback, failed-handle and renderer-cache cases");
}
#endif
