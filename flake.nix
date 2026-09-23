{
  description = "Project devShell composed with Finix presets";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs =
    { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
      };

      brave-mcp-bundle = import ./nix/brave-mcp.nix {
        inherit pkgs;
        nodejs = pkgs.nodejs_24;
      };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          nodejs_24
          brave-mcp-bundle
        ];

        shellHook = ''
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
