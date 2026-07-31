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

        # Google Play Console CLI. Not in nixpkgs, and its own installer writes into
        # the home directory, so pin the upstream release binary instead — it is a
        # static Go build and needs no patching.
        gplayVersion = "0.8.0";
        gplayAssets = {
          x86_64-linux = {
            asset = "gplay-linux-amd64";
            sha256 = "ea9d812878dcc61faa0401a306f3f648ab6779d87358e07afb8590e9c96a313a";
          };
          aarch64-linux = {
            asset = "gplay-linux-arm64";
            sha256 = "f403b622cdf58295554ee6d2e5984bfbbeac82bdd304397c991243476a7d8d46";
          };
          x86_64-darwin = {
            asset = "gplay-darwin-amd64";
            sha256 = "afab6397d65a84bbc4f7f91a333e5cbdbdd2535bd3bdf6ad8ac7cc5e61d45366";
          };
          aarch64-darwin = {
            asset = "gplay-darwin-arm64";
            sha256 = "b6b7b016c68088d9cf7d8d66566395c8c6b6f984e8b1a69624089aaaae3950fa";
          };
        };

        gplay = pkgs.lib.optional (gplayAssets ? ${system}) (
          let a = gplayAssets.${system}; in
          pkgs.runCommand "gplay-${gplayVersion}" { } ''
            install -D -m555 ${pkgs.fetchurl {
              url = "https://github.com/tamtom/play-console-cli/releases/download/v${gplayVersion}/${a.asset}";
              inherit (a) sha256;
            }} $out/bin/gplay
          ''
        );
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            jdk
            pkgs.gradle_9
            androidSdk.androidsdk
            pkgs.git
            # gplay's own setup would curl gcloud into $HOME; take it from nixpkgs.
            pkgs.google-cloud-sdk
          ] ++ gplay;

          shellHook = ''
            export JAVA_HOME="${jdk.home}"
            export ANDROID_HOME="${sdkPath}"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="$ANDROID_HOME/platform-tools:$PATH"

            # AGP downloads its own aapt2 from Maven; that binary is dynamically linked
            # and only runs on NixOS when programs.nix-ld is enabled. Pointing it at the
            # SDK's own (patched) aapt2 makes the shell work either way.
            export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/${buildToolsVersion}/aapt2 ''${GRADLE_OPTS:-}"

            # gplay self-updates in place on startup, which cannot work from the
            # read-only store — the version above is the pin.
            export GPLAY_NO_UPDATE=1

            echo "ylih dev shell"
            echo "  JAVA_HOME    = $JAVA_HOME"
            echo "  ANDROID_HOME = $ANDROID_HOME"
            echo "  build with:    ./gradlew assembleDebug"
          '';
        };
      }
    );
}
