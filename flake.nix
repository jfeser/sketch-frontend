{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    flake-utils.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, flake-utils, nixpkgs }@inputs:
    let package = "sketch";
        version = "1.7.6";
    in flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system}; in
      {
      defaultPackage = pkgs.stdenv.mkDerivation {
        pname = package;
        version = version;

        src = pkgs.fetchurl {
          urls = [ "https://people.csail.mit.edu/asolar/sketch-${version}.tar.gz" ];
          sha256 = "01x75pkxlv74s1lscdd6w1nxzjrq3av8rbgrbwhk5m8zhk7cknjw";
        };

        nativeBuildInputs = [
          pkgs.bison
          pkgs.flex
        ];

        buildInputs = [
          pkgs.openjdk11_headless
        ];

        enableParallelBuilding = true;
        preConfigure = ''cd sketch-backend'';
        postInstall = ''
    cd ../sketch-frontend
    cp sketch sketch-${version}-noarch.jar $out/bin/
    mkdir -p $out/share
    cp -r sketchlib runtime $out/share/
  '';
      };
    });
}
