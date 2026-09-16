{ pkgs, nodejs }:

let
  braveDevtoolsMcp = pkgs.stdenv.mkDerivation rec {
    pname = "brave-devtools-mcp";
    version = "1.9.0-pr30";

    src = pkgs.fetchurl {
      url = "https://registry.npmjs.org/brave-mcp/-/brave-mcp-1.9.0.tgz";
      hash = "sha512-3djUoXjx5+ePi1f3SfOvT4Bu6F3wb353gMvkxE51VmD0VR4aaCyn20itu4KzucU+tsP3QsXwEBUj2per/0t/Jw==";
    };

    nativeBuildInputs = [
      pkgs.makeWrapper
      nodejs
    ];

    # Apply PR #30 changes directly to compiled sources
    postPatch = ''
      ${nodejs}/bin/node -e '
        const fs = require("fs");
        const path = require("path");
        const filePath = "build/src/browser.js";
        if (fs.existsSync(filePath)) {
          let code = fs.readFileSync(filePath, "utf8");

          // 1. Add brave-origin candidates to linux release channel
          code = code.replace(
            /release:\s*\[[\x27"]brave-browser[\x27"],\s*[\x27"]brave-browser-stable[\x27"]\]/,
            "release: [\x27brave-browser\x27, \x27brave-browser-stable\x27, \x27brave-origin\x27, \x27/opt/brave-origin-bin/brave\x27]"
          );

          // 2. Support absolute path candidates
          code = code.replace(
            /const resolvedPath = \(0,\s*child_process_1\.execSync\)\(/,
            "if (path.isAbsolute(candidate)) { if (fs.existsSync(candidate)) return candidate; continue; }\n const resolvedPath = (0, child_process_1.execSync)("
          );

          // 3. Add Brave-Origin profile directory support & DevToolsActivePort discovery
          code = code.replace(
            /release:\s*([a-zA-Z0-9_$.]+)\.join\(configDir,\s*[\x27"]BraveSoftware[\x27"],\s*[\x27"]Brave-Browser[\x27"]\)/,
            "release: [$1.join(configDir, \x27BraveSoftware\x27, \x27Brave-Browser\x27), $1.join(configDir, \x27BraveSoftware\x27, \x27Brave-Origin\x27)]"
          );

          code = code.replace(
            /return dirs\[channel \?\? [\x27"]release[\x27"]\];/g,
            "const rawCandidates = dirs[channel ?? \x27release\x27]; const candidates = Array.isArray(rawCandidates) ? rawCandidates : [rawCandidates]; return candidates.find(c => fs.existsSync(path.join(c, \x27DevToolsActivePort\x27))) ?? candidates.find(c => fs.existsSync(c)) ?? candidates[0];"
          );

          fs.writeFileSync(filePath, code);
        }
      '
    '';

    installPhase = ''
      runHook preInstall

      mkdir -p $out/lib/node_modules/brave-mcp
      cp -r . $out/lib/node_modules/brave-mcp

      mkdir -p $out/bin
      makeWrapper ${nodejs}/bin/node $out/bin/brave-devtools-mcp \
        --add-flags "$out/lib/node_modules/brave-mcp/build/src/bin/brave-devtools-mcp.js"

      ln -s $out/bin/brave-devtools-mcp $out/bin/brave-mcp
      ln -s $out/bin/brave-devtools-mcp $out/bin/brave-devtools

      runHook postInstall
    '';
  };
in
braveDevtoolsMcp
