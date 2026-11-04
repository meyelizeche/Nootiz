{
  description = "Nootiz Android Client development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          buildToolsVersion = "36.1.0";

          androidComposition = pkgs.androidenv.composeAndroidPackages {
            cmdLineToolsVersion = "latest";
            platformToolsVersion = "latest";
            toolsVersion = null;
            buildToolsVersions = [ "36.0.0" buildToolsVersion ];
            platformVersions = [ "36" "36.1" ];
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
            includeCmake = false;
            includeSources = false;
          };

          androidSdk = androidComposition.androidsdk;
          androidSdkRoot = "${androidSdk}/libexec/android-sdk";
          aapt2Path = "${androidSdkRoot}/build-tools/${buildToolsVersion}/aapt2";
          jdk = pkgs.jdk21;
        in
        {
          default = pkgs.mkShell {
            packages = [
              androidSdk
              jdk
              pkgs.gradle_9
              pkgs.kotlin
            ];

            JAVA_HOME = "${jdk.home}";
            ANDROID_HOME = androidSdkRoot;
            ANDROID_SDK_ROOT = androidSdkRoot;
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Path}";

            shellHook = ''
              export PATH="${androidSdkRoot}/build-tools/${buildToolsVersion}:${androidSdkRoot}/platform-tools:$PATH"
              if [ -w . ] && { [ ! -f local.properties ] || ! grep -q "^sdk.dir=${androidSdkRoot}$" local.properties; }; then
                printf "sdk.dir=%s\n" "${androidSdkRoot}" > local.properties
              fi
            '';
          };
        }
      );
    };
}

