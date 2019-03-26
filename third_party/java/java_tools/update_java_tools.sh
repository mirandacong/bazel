#!/bin/bash -e
#
# A script to update the checked-in jars corresponding to Java tools used
# by the Java rules in Bazel.
#
# For usage please run
# third_party/java/java_tools/update_java_tools.sh help

# Maps the java tool names to their associated bazel target.
declare -A tool_name_to_target=(["JavaBuilder"]="src/java_tools/buildjar:JavaBuilder_deploy.jar" \
["VanillaJavaBuilder"]="src/java_tools/buildjar:VanillaJavaBuilder_deploy.jar" \
["GenClass"]="src/java_tools/buildjar/java/com/google/devtools/build/buildjar/genclass:GenClass_deploy.jar" \
["Runner"]="src/java_tools/junitrunner/java/com/google/testing/junit/runner:Runner_deploy.jar" \
["ExperimentalRunner"]="src/java_tools/junitrunner/java/com/google/testing/junit/runner:ExperimentalRunner_deploy.jar" \
["JacocoCoverage"]="src/java_tools/junitrunner/java/com/google/testing/coverage:JacocoCoverage_jarjar_deploy.jar" \
["Turbine"]="src/java_tools/buildjar/java/com/google/devtools/build/java/turbine/javac:turbine_deploy.jar" \
["TurbineDirect"]="src/java_tools/buildjar/java/com/google/devtools/build/java/turbine:turbine_direct_binary_deploy.jar" \
["SingleJar"]="src/java_tools/singlejar/java/com/google/devtools/build/singlejar:bazel-singlejar_deploy.jar"
["JarJar"]="third_party/jarjar:jarjar_command_deploy.jar")

usage="This script updates the checked-in jars corresponding to the tools "\
"used by the Java rules in Bazel.

To update all the tools simultaneously run from your bazel workspace root:
third_party/java/java_tools/update_java_tools.sh

WARNING: This script also allows updating individual tools, but it is not recommended
to do so, because it involves creating the sources archive manually from
different commits in bazel. Prefer to update all the tools, even though you
only target one of them.

To update only one or one subset of the tools run
third_party/java/java_tools/update_java_tools.sh tool_1 tool_2 ... tool_n

where tool_i can have one of the values: ${!tool_name_to_target[*]}.

For example, to update only JavaBuilder run
third_party/java/java_tools/update_java_tools.sh JavaBuilder

To update JavaBuilder, Turbine and SingleJar run
third_party/java/java_tools/update_java_tools.sh JavaBuilder Turbine SingleJar
"

if [[ ! -z "$1" && $1 = "help" ]]; then
  echo "$usage"
  exit
fi

# Stores the names of the tools required for update.
tools_to_update=()

# Stores the workspace relative path of all the tools that were updated
# (e.g. third_party/java/java_tools/JavaBuilder_deploy.jar)
updated_tools=()

if [[ ! -z "$@" ]]
then
   # Update only the tools specified on the command line.
   tools_to_update=("$@")
   echo "The sources archive was NOT created. Please create it manually!"
else
  # If no tools were specified update all of them.
  tools_to_update=(${!tool_name_to_target[*]})
  # Create the sources archive only when all the tools were updated.
  # TODO(iirina): Find another archiving method that is reproducible
  # (e.g. doesn't include timestamps).
  zip -Xr third_party/java/java_tools/java_tools-srcs.zip src/java_tools/buildjar/* \
  src/java_tools/junitrunner/* src/java_tools/singlejar/* third_party/jarjar/* \
  third_party/java/jdk/langtools/LICENSE \
  third_party/java/jdk/langtools/java_compiler-src.jar \
  third_party/java/jdk/langtools/javac-9+181-r4173-1.srcjar \
  third_party/java/jdk/langtools/jdk_compiler-src.jar \
  echo "Created sources archive third_party/java/java_tools/java_tools-srcs.zip"
  updated_tools+=("third_party/java/java_tools/java_tools-srcs.zip")
fi


# Updates the tool with the given bazel target.
#
# Builds the given bazel target and copies the generated binary
# (which can be either under bazel-bin/ or bazel-genfiles/) under
# third_party/java/java_tools.
#
# Fails if the bazel build fails.
#
# bazel_target   The target to be built with bazel.
# tool_name      The name of the tool associated with the given bazel
#                target. Used only for printing error messages.
function update_tool() {
  local bazel_target="${1}"; shift
  local tool_name="${1}"; shift
  bazel build "$bazel_target" || (echo "Could not build $tool_name.
  Please see the Bazel error logs above." && exit 1)

  local binary=$(echo "bazel-bin/$bazel_target" | sed 's@:@/@')

  if [[ ! -f "$binary" ]]; then
    binary=$(echo "bazel-genfiles/$bazel_target" | sed 's@:@/@')
  fi

  local tool_basename=$(basename $binary)
  if [[ -f "$binary" ]]; then
    cp -f "$binary" "third_party/java/java_tools/$tool_basename"
    updated_tools+=("third_party/java/java_tools/$tool_basename")
  fi
}

# Updating the specified tools.
for tool in "${tools_to_update[@]}"
do
  # Get the bazel target associated with the current tool name.
  tool_bazel_target=${tool_name_to_target[$tool]}
  [[ -z "$tool_bazel_target" ]] && echo "Tool $tool is not supported.
  Please specify one or more of: ${!tool_name_to_target[*]}." && exit 1
  update_tool "$tool_bazel_target" "$tool"
done

if [[ ${#updated_tools[@]} -gt 0 ]]; then
  bazel_version=$(bazel version | grep "Build label" | cut -d " " -f 3)
  git_head=$(git rev-parse HEAD)
  cat >third_party/java/java_tools/README.md <<EOL

The following tools were built with bazel $bazel_version at commit $git_head
by running:
$ third_party/java/java_tools/update_java_tools.sh $@

$( IFS=$'\n'; echo "${updated_tools[*]}" )
EOL

echo "IMPORTANT: Make sure that third_party/java/java_tools/java_tools-srcs.zip was created \
or create it manually!"
fi
