{
  description = "Project devShell composed with Finix presets";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    { nixpkgs, android-nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true; # 避免 Android SDK 元件授權阻擋
      };

      brave-mcp-bundle = import ./nix/brave-mcp.nix {
        inherit pkgs;
        nodejs = pkgs.nodejs_24;
      };

      androidSdk = android-nixpkgs.sdk.${system} (sdkPkgs: with sdkPkgs; [
        cmdline-tools-13-0
        build-tools-35-0-0
        platform-tools
        platforms-android-35
      ]);
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          nodejs_24
          # jdk17
          androidSdk
          brave-mcp-bundle
          android-cli
        ];

        env = {
          JAVA_HOME = "${pkgs.jdk17.home}";
          GRADLE_OPTS = "-Dorg.gradle.daemon=true";

          ANDROID_HOME = "${androidSdk}/share/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/share/android-sdk";
        };

        shellHook = ''
          # 解決 NixOS 上 Gradle 使用 aapt2 缺少動態連結庫的問題
          export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath [ pkgs.vulkan-loader pkgs.libglvnd ]}:''${LD_LIBRARY_PATH:-}"

          # Discover and configure BRAVE_PATH for brave-origin
          if [ -z "''${BRAVE_PATH:-}" ]; then
            for candidate in brave-origin /opt/brave-origin-bin/brave brave-browser brave-browser-stable brave; do
              if command -v "$candidate" >/dev/null 2>&1; then
                export BRAVE_PATH="$(command -v "$candidate")"
                break
              elif [ -x "$candidate" ]; then
                export BRAVE_PATH="$candidate"
                break
              fi
            done
          fi

          if [ -n "''${BRAPH_PATH:-}" ] || [ -n "''${BRAVE_PATH:-}" ]; then
            echo "🧭 [Brave DevTools MCP] BRAVE_PATH set to: $BRAVE_PATH"
          else
            echo "⚠️ [Brave DevTools MCP] Warning: brave-origin / brave binary not found in PATH or standard paths."
          fi
        '';
      };
    };
}
