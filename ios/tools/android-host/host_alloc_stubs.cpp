// Host build of the app's liballocator: the hardware-buffer half (scam-ahb.cpp, AHardwareBuffer) has no host; no buffer is ever one.
bool scamAhbFreeView(const void*) { return false; }
