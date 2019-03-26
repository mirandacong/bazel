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

package com.google.devtools.build.lib.bazel.rules.cpp;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.skylark.SkylarkRuleContext;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper.CompilationInfo;
import com.google.devtools.build.lib.rules.cpp.CcCompilationOutputs;
import com.google.devtools.build.lib.rules.cpp.CcLinkingHelper.LinkingInfo;
import com.google.devtools.build.lib.rules.cpp.CcModule;
import com.google.devtools.build.lib.rules.cpp.CcToolchainConfigInfo;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainProvider;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink.CcLinkingContext;
import com.google.devtools.build.lib.skylarkbuildapi.cpp.BazelCcModuleApi;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;

/**
 * A module that contains Skylark utilities for C++ support.
 *
 * <p>This is a work in progress. The API is guarded behind
 * --experimental_cc_skylark_api_enabled_packages. The API is under development and unstable.
 */
public class BazelCcModule extends CcModule
    implements BazelCcModuleApi<
        Artifact,
        SkylarkRuleContext,
        CcToolchainProvider,
        FeatureConfiguration,
        CompilationInfo,
        CcCompilationContext,
        CcCompilationOutputs,
        LinkingInfo,
        CcLinkingContext,
        LibraryToLink,
        CcToolchainVariables,
        CcToolchainConfigInfo> {

  @Override
  public CompilationInfo compile(
      SkylarkRuleContext skylarkRuleContext,
      FeatureConfiguration skylarkFeatureConfiguration,
      CcToolchainProvider skylarkCcToolchainProvider,
      SkylarkList<Artifact> sources,
      SkylarkList<Artifact> headers,
      Object skylarkIncludes,
      Object skylarkCopts,
      SkylarkList<CcCompilationContext> ccCompilationContexts,
      Location location)
      throws EvalException, InterruptedException {
    return BazelCcModule.compile(
        BazelCppSemantics.INSTANCE,
        skylarkRuleContext,
        skylarkFeatureConfiguration,
        skylarkCcToolchainProvider,
        sources,
        headers,
        skylarkIncludes,
        skylarkCopts,
        /* generateNoPicOutputs= */ "conditionally",
        /* generatePicOutputs= */ "conditionally",
        /* skylarkAdditionalCompilationInputs= */ Runtime.NONE,
        /* skylarkAdditionalIncludeScanningRoots= */ Runtime.NONE,
        ccCompilationContexts,
        /* purpose= */ Runtime.NONE,
        location);
  }

  @Override
  public LinkingInfo link(
      SkylarkRuleContext skylarkRuleContext,
      FeatureConfiguration skylarkFeatureConfiguration,
      CcToolchainProvider skylarkCcToolchainProvider,
      CcCompilationOutputs ccCompilationOutputs,
      Object skylarkLinkopts,
      Object dynamicLibrary,
      SkylarkList<CcLinkingContext> skylarkCcLinkingContexts,
      boolean neverLink)
      throws InterruptedException, EvalException {
    return BazelCcModule.link(
        BazelCppSemantics.INSTANCE,
        skylarkRuleContext,
        skylarkFeatureConfiguration,
        skylarkCcToolchainProvider,
        ccCompilationOutputs,
        skylarkLinkopts,
        /* shouldCreateStaticLibraries= */ true,
        dynamicLibrary,
        skylarkCcLinkingContexts,
        neverLink);
  }
}
