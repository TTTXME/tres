#pragma once

namespace spektrafilm {
enum class QueuePolicy { Legacy = 0, Interactive = 1 };

template<class State, class Directory, class Factory>
void selectCachedFilmRenderer(State& state, const Directory& directory,
                              QueuePolicy policy, Factory create) {
  if (state.renderer && state.resourceDirectory == directory && state.queuePolicy == policy) return;
  state.renderer.reset();
  state.renderer = create();
  state.resourceDirectory = directory;
  state.queuePolicy = policy;
}
}
