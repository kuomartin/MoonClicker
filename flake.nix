{
  description = "Project devShell composed with Finix presets";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    systems.url = "github:nix-systems/default";
    flake-utils = {
      url = "github:numtide/flake-utils";
      inputs.systems.follows = "systems";
    };
    finix.url = "git+ssh://git@github.com/kuomartin/finix-config.git";
  };

  outputs =
    { nixpkgs, flake-utils, finix, ... }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          inputsFrom = [
            finix.devShells.${system}.node
            finix.devShells.${system}.android
          ];

          packages = with pkgs; [
            nodejs_24
          ];
        };
      }
    );
}