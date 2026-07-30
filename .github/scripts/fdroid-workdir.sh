#!/usr/bin/env bash
#
# Build the directory fdroidserver insists on running from: a git repository shaped like
# fdroiddata, holding our recipe and enough configuration to build with.
#
# Every piece below exists because leaving it out fails, usually confusingly:
#
#   - it must be a *git* repository. `fdroid build` derives SOURCE_DATE_EPOCH from the commit
#     that last touched metadata/<appid>.yml, and with no git repository that lookup returns
#     None and the build dies in os.environ with "str expected, not NoneType".
#   - config/categories.yml is what `fdroid lint` validates Categories against. Its upstream
#     copy names an icon per category and lint copies those icons, so the icon lines are dropped
#     rather than the images fetched.
#   - gradlew-fdroid is the wrapper F-Droid substitutes for ours — it deletes gradlew and
#     gradle-wrapper.jar during the build and runs this instead, resolving the Gradle version
#     from distributionUrl against a transparency log of known checksums. It moved out of
#     fdroidserver into its own repository, and the copy still bundled in the 2.4.5 release is
#     the old one, whose hardcoded table stops at Gradle 8.14.2 and cannot build this app.
#     Cloning it is what the real buildserver does (buildserver/provision-gradle).
#
# Usage: .github/scripts/fdroid-workdir.sh <target-dir>
set -euo pipefail

target="${1:?usage: fdroid-workdir.sh <target-dir>}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# The target is rebuilt from scratch each run, so it is worth being sure about what is being
# deleted: anything that is not already one of these directories is somebody's real work.
if [ -e "$target" ] && [ ! -e "$target/config.yml" ]; then
  echo "refusing to delete $target: it exists and does not look like an fdroiddata workdir" >&2
  exit 1
fi

rm -rf "$target"
mkdir -p "$target/metadata" "$target/config"
cd "$target"

cp "$repo_root/metadata/it.eldavo.ylih.yml" metadata/

curl --fail --silent --show-error --location --retry 3 \
  https://gitlab.com/fdroid/fdroiddata/-/raw/master/config/categories.yml \
  | grep -v '^  icon: ' > config/categories.yml

git clone --depth 1 --quiet https://gitlab.com/fdroid/gradlew-fdroid.git gradlew-fdroid
chmod 0755 gradlew-fdroid/gradlew-fdroid

{
  echo "repo_url: https://example.com/fdroid/repo"
  echo "repo_name: ylih CI"
  echo "repo_description: not a real repository; fdroidserver requires these to be set"
  echo "archive_older: 0"
  echo "gradle: $target/gradlew-fdroid/gradlew-fdroid"
  # Only the build job has an SDK. Naming a path that does not exist makes fdroidserver complain
  # on every command, including the ones that never touch it.
  if [ -n "${ANDROID_HOME:-}" ]; then
    echo "sdk_path: $ANDROID_HOME"
  fi
} > config.yml

# The buildserver writes exactly these two into its Gradle home, and they change what a build
# does rather than just how fast it is: a daemon would survive between builds, and toolchain
# auto-provisioning would silently fetch a JDK other than the one the image provides.
#
# This goes inside the target directory rather than into ~/.gradle, which the buildserver can
# own outright and a developer's machine cannot. Point GRADLE_USER_HOME at it to use it.
mkdir -p gradle-home
cat > gradle-home/gradle.properties <<'EOF'
org.gradle.daemon=false
org.gradle.java.installations.auto-download=false
EOF

git init --quiet --initial-branch=main .
git -c user.email=ci@example.com -c user.name=CI \
    -c commit.gpgsign=false \
    -c init.defaultBranch=main \
    add metadata config.yml config/categories.yml
git -c user.email=ci@example.com -c user.name=CI -c commit.gpgsign=false \
    commit --quiet -m "ylih recipe under test"

echo "fdroiddata-shaped directory ready at $target"
