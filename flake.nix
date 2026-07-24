{
  description = "ylih — headphone connection time tracker (Android dev environment)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Keep in sync with gradle/libs.versions.toml and .github/workflows/android-ci.yml.
        buildToolsVersion = "37.0.0";
        platformVersion = "37.0";

        androidSdk = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ platformVersion ];
          buildToolsVersions = [ buildToolsVersion ];
          includeEmulator = false;
          includeSystemImages = false;
        };

        sdkPath = "${androidSdk.androidsdk}/libexec/android-sdk";
        jdk = pkgs.jdk21;
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            jdk
            pkgs.gradle_9
            androidSdk.androidsdk
            pkgs.git
          ];

          shellHook = ''
            export JAVA_HOME="${jdk.home}"
            export ANDROID_HOME="${sdkPath}"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="$ANDROID_HOME/platform-tools:$PATH"

            # AGP downloads its own aapt2 from Maven; that binary is dynamically linked
            # and only runs on NixOS when programs.nix-ld is enabled. Pointing it at the
            # SDK's own (patched) aapt2 makes the shell work either way.
            export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/${buildToolsVersion}/aapt2 ''${GRADLE_OPTS:-}"

            echo "ylih dev shell"
            echo "  JAVA_HOME    = $JAVA_HOME"
            echo "  ANDROID_HOME = $ANDROID_HOME"
            echo "  build with:    ./gradlew assembleDebug"
          '';
        };
      }
    );
}
