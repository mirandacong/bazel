// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableList;
import java.util.Collection;

/**
 * Association between {@link ActionInput}s and the {@link Artifact}s, directly depended on by an
 * action, that are responsible for that action's inclusion of those inputs.
 */
public interface ActionInputDepOwners {

  /** An {@link ActionInputDepOwners} without any ownership associations. */
  ActionInputDepOwners EMPTY_INSTANCE = input -> ImmutableList.of();

  /**
   * Returns the collection of {@link Artifact}s associated with {@code input}. The collection is
   * empty if no such association exists.
   */
  Collection<Artifact> getDepOwners(ActionInput input);
}
